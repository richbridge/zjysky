package com.zjysky.crawler.pipeline;

import com.zjysky.crawler.config.CrawlerConfig;
import com.zjysky.crawler.config.DbConfig;
import com.zjysky.crawler.mapper.ShfeMapper;
import com.zjysky.crawler.model.MdPriceShfe;
import com.zjysky.crawler.support.CloseTimeGuard;
import com.zjysky.crawler.support.CrawlMetaSupport;
import com.zjysky.crawler.support.Finalized;
import com.zjysky.crawler.support.PriceSupport;
import com.zjysky.crawler.support.RealtimeOverwrite;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import us.codecraft.webmagic.ResultItems;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.pipeline.Pipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 四大组件之一：Pipeline。
 * 职责：① 启动前对目标表做字段完整性自检（表结构变更时立刻报错，不静默丢字段）
 *      ② 通过 MyBatis-Plus 的 ShfeMapper 批量 upsert 到 md_price_shfe
 *      ③ 输出按 row_type 分组的采集摘要。
 */
public class ShfePipeline implements Pipeline {

    /** 本次 INSERT 用到的全部列（33 列），缺一不可 */
    private static final List<String> REQUIRED = List.of(
            "task_type", "row_type", "trade_date", "symbol",
            "contract_month", "product_name", "product_id", "product_group", "product_class",
            "open_price", "high_price", "low_price", "close_price", "last_price", "pre_close_price",
            "settle_price", "pre_settle_price", "avg_price", "change_value", "change_value2", "change_pct",
            "volume", "tas_volume", "last_volume", "open_interest", "open_interest_change",
            "turnover", "year_volume", "year_turnover", "bid_price", "ask_price",
            "source_update_time", "raw_json");

    private final SqlSessionFactory factory;
    /** 本次写入的目标表，收盘后清理同日实时行时也要用 */
    private final String table;

    public ShfePipeline() {
        this.factory = DbConfig.getSqlSessionFactory();
        this.table = CrawlerConfig.str("crawler.shfe.table", "md_price_shfe");
        try (SqlSession session = factory.openSession(true)) {
            List<String> columns = session.getMapper(ShfeMapper.class).listColumns(table);
            if (columns == null || columns.isEmpty()) {
                throw new IllegalStateException("表 " + table + " 不存在或读不到字段，请检查 crawler.shfe.table");
            }
            Set<String> actual = columns.stream()
                    .map(c -> c.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            List<String> missing = REQUIRED.stream()
                    .filter(c -> !actual.contains(c))
                    .toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("表 " + table + " 字段不完整，缺少: " + missing);
            }
            Set<String> unused = new TreeSet<>(actual);
            unused.removeAll(REQUIRED);
            System.out.println(">>> [Schema] 字段完整性检测通过: " + table
                    + " 共 " + columns.size() + " 列，本次写入所需 " + REQUIRED.size() + " 列齐全"
                    + "；未使用列（由 DB 默认值/其它任务维护）: " + unused);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(">>> [Pipeline] 字段自检失败：" + e.getMessage(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(ResultItems resultItems, Task task) {
        long begin = System.currentTimeMillis();
        List<MdPriceShfe> list = resultItems.get("shfeList");
        if (list == null || list.isEmpty()) {
            return;
        }
        String tradeDate = list.get(0).getTradeDate();
        String taskType = list.get(0).getTaskType();

        // 收盘口径必须「价格已定稿」才写库。SHFE 的 kx{yyyyMMdd}.dat 在盘中也会返回 200，
        // 但收盘价基本是空、结算价全是空，等于半成品：
        // 写进去会污染当天数据，还会把已经采到的实时行删掉，所以这里直接放弃本次落库。
        if (CloseTimeGuard.isCloseTaskType(taskType)) {
            int priced = (int) list.stream().filter(d -> d.getClosePrice() != null).count();
            int settled = (int) list.stream().filter(d -> d.getSettlePrice() != null).count();
            if (!Finalized.shfe(list.size(), priced, settled)) {
                String msg = "跳过：收盘数据尚未定稿（" + Finalized.describe(list.size(), priced, settled) + "）";
                System.out.println(">>> [Pipeline] " + tradeDate + " " + msg);
                CrawlMetaSupport.saveLog(factory, "SHFE", taskType, false, 0,
                        System.currentTimeMillis() - begin, msg);
                return;
            }
        }

        try (SqlSession session = factory.openSession(true)) {
            ShfeMapper mapper = session.getMapper(ShfeMapper.class);
            int affected = mapper.upsertBatch(list);
            System.out.println(">>> [Pipeline] 落库完成: " + tradeDate
                    + " 共 " + list.size() + " 条（影响行数 " + affected + "）");
        } catch (Exception e) {
            if (PriceSupport.isClosedDataSource(e)) {
                System.err.println(">>> [Pipeline] 数据源已关闭，跳过本次写入（应用可能正在重启）");
                return;
            }
            System.err.println(">>> [Pipeline] 落库失败: " + e.getMessage());
            CrawlMetaSupport.saveLog(factory, "SHFE", taskType, false, 0,
                    System.currentTimeMillis() - begin, "SHFE 落库失败: " + e.getMessage());
            throw new IllegalStateException(e);
        }

        // 采集元信息：字段字典 + 成功日志（写失败绝不影响上面的行情落库）
        long costMs = System.currentTimeMillis() - begin;
        CrawlMetaSupport.saveFields(factory, "SHFE", taskType,
                list.stream().map(MdPriceShfe::getRawJson).toList());
        CrawlMetaSupport.saveLog(factory, "SHFE", taskType, true, list.size(),
                costMs, CrawlMetaSupport.message(list.size(), costMs));

        // 需求 5：收盘历史（QUOTE）落库成功后，删掉同日早先采的 REALTIME 行，
        // 让库里最终只留价格已定稿的收盘数据。只处理「本次确实写到了价格」的交易日。
        Map<String, Integer> pricedByDate = new LinkedHashMap<>();
        for (MdPriceShfe d : list) {
            if (d.getClosePrice() != null) {
                pricedByDate.merge(d.getTradeDate(), 1, Integer::sum);
            }
        }
        RealtimeOverwrite.afterCloseWrite(factory, "SHFE", table, taskType, pricedByDate);

        Map<String, Long> byType = new LinkedHashMap<>();
        list.forEach(d -> byType.merge(d.getRowType(), 1L, Long::sum));
        int sampleSize = CrawlerConfig.intVal("crawler.shfe.sample-size", 5);
        System.out.println("=== SHFE " + tradeDate + " 共 " + list.size() + " 条 " + byType + "，样例 ===");
        list.stream().limit(sampleSize).forEach(System.out::println);
        if (list.size() > sampleSize) {
            System.out.println("…… 其余 " + (list.size() - sampleSize) + " 条已落库");
        }
    }
}
