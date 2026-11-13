package com.zjysky.crawler.pipeline;

import com.zjysky.common.database.mapper.MdPriceShfeMapper;
import com.zjysky.common.database.entity.MdPriceShfe;
import com.zjysky.common.database.entity.MdSourceProduct;
import com.zjysky.crawler.guard.CloseTimeGuard;
import com.zjysky.crawler.PriceSupport;
import org.apache.ibatis.session.SqlSession;
import us.codecraft.webmagic.ResultItems;
import us.codecraft.webmagic.Task;

import java.util.ArrayList;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四大组件之一：Pipeline（SHFE 上期所 → md_price_shfe）。
 * 职责：① 启动前对目标表做字段完整性自检（表结构变更时立刻报错，不静默丢字段）
 *      ② 通过 MdPriceShfeMapper.upsertBatch 批量 upsert 到 md_price_shfe
 *      ③ 收盘落库后清理同日实时行；输出按 row_type 分组的采集摘要。
 */
public class ShfePipeline extends AbstractPricePipeline {

    /** 本次 INSERT 用到的全部列（33 列），缺一不可 */
    private static final List<String> REQUIRED = List.of(
            "task_type", "row_type", "trade_date", "symbol",
            "contract_month", "product_name", "product_id", "product_group", "product_class",
            "open_price", "high_price", "low_price", "close_price", "last_price", "pre_close_price",
            "settle_price", "pre_settle_price", "avg_price", "change_value", "change_value2", "change_pct",
            "volume", "tas_volume", "last_volume", "open_interest", "open_interest_change",
            "turnover", "year_volume", "year_turnover", "bid_price", "ask_price",
            "source_update_time", "raw_json");

    public ShfePipeline() {
        super("SHFE", "crawler.shfe.table", "md_price_shfe", REQUIRED);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(ResultItems resultItems, Task task) {
        long begin = System.currentTimeMillis();
        List<MdPriceShfe> list = resultItems.get("shfeList");
        if (list == null || list.isEmpty()) {
            return;
        }
        LocalDate tradeDate = list.get(0).getTradeDate();
        String taskType = list.get(0).getTaskType();

        // 收盘口径必须「价格已定稿」才写库。SHFE 的 kx{yyyyMMdd}.dat 在盘中也会返回 200，
        // 但收盘价基本是空、结算价全是空，等于半成品：
        // 写进去会污染当天数据，还会把已经采到的实时行删掉，所以这里直接放弃本次落库。
        if (CloseTimeGuard.isCloseTaskType(taskType)) {
            int priced = (int) list.stream().filter(d -> d.getClosePrice() != null).count();
            int settled = (int) list.stream().filter(d -> d.getSettlePrice() != null).count();
            if (!CloseTimeGuard.finalizedShfe(list.size(), priced, settled)) {
                String msg = "跳过：收盘数据尚未定稿（"
                        + CloseTimeGuard.describeFinalized(list.size(), priced, settled) + "）";
                System.out.println(">>> [Pipeline] " + tradeDate + " " + msg);
                saveMeta(taskType, false, 0, System.currentTimeMillis() - begin, msg, null);
                return;
            }
        }

        int affected;
        try {
            affected = upsert(list);
            System.out.println(">>> [Pipeline] 落库完成: " + tradeDate
                    + " 共 " + list.size() + " 条（影响行数 " + affected + "）");
        } catch (Exception e) {
            if (PriceSupport.isClosedDataSource(e)) {
                System.err.println(">>> [Pipeline] 数据源已关闭，跳过本次写入（应用可能正在重启）");
                return;
            }
            System.err.println(">>> [Pipeline] 落库失败: " + e.getMessage());
            saveMeta(taskType, false, 0, System.currentTimeMillis() - begin,
                    "SHFE 落库失败: " + e.getMessage(), null);
            throw new IllegalStateException(e);
        }

        // 需求「合约信息采集价格时顺便更新完善」：从本次行情行提取合约字典
        // （同一 PRODUCTID 可能对应多行合约/小计，按 productId 去重），upsert 到 md_source_product。
        // 写入失败只打日志，不影响上面的行情落库。
        List<MdSourceProduct> products = new ArrayList<>();
        java.util.Set<String> seenProducts = new java.util.HashSet<>();
        for (MdPriceShfe d : list) {
            if (d.getProductId() == null || d.getProductId().isBlank()
                    || !seenProducts.add(d.getProductId())) {
                continue;
            }
            MdSourceProduct p = new MdSourceProduct();
            p.setSource("SHFE");
            p.setProductId(d.getProductId());
            p.setProductName(d.getProductName() == null || d.getProductName().isBlank()
                    ? d.getProductId() : d.getProductName());
            p.setProductGroup(d.getProductGroup());
            p.setEnabled(1);
            products.add(p);
        }
        writeProducts(products);

        // 采集元信息：字段字典 + 成功日志（写失败绝不影响上面的行情落库）
        long costMs = System.currentTimeMillis() - begin;
        saveMeta(taskType, true, list.size(), costMs, null,
                list.stream().map(MdPriceShfe::getRawJson).toList());

        // 收盘历史（QUOTE）落库成功后，删掉同日早先采的 REALTIME 行，
        // 让库里最终只留价格已定稿的收盘数据。只处理「本次确实写到了价格」的交易日。
        Map<String, Integer> pricedByDate = new LinkedHashMap<>();
        for (MdPriceShfe d : list) {
            if (d.getClosePrice() != null) {
                pricedByDate.merge(d.getTradeDate().toString(), 1, Integer::sum);
            }
        }
        overwriteRealtime(taskType, pricedByDate);

        Map<String, Long> byType = new LinkedHashMap<>();
        list.forEach(d -> byType.merge(d.getRowType(), 1L, Long::sum));
        int sampleSize = com.zjysky.crawler.CrawlerConfig.intVal("crawler.shfe.sample-size", 5);
        System.out.println("=== SHFE " + tradeDate + " 共 " + list.size() + " 条 " + byType + "，样例 ===");
        list.stream().limit(sampleSize).forEach(System.out::println);
        if (list.size() > sampleSize) {
            System.out.println("…… 其余 " + (list.size() - sampleSize) + " 条已落库");
        }
    }

    private int upsert(List<MdPriceShfe> list) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(MdPriceShfeMapper.class).upsertBatch(list);
        }
    }

    /** 收盘定稿后删除同日 REALTIME 行（crawler.market-close.realtime-task-type）。 */
    @Override
    protected int deleteRealtimeByDate(String tradeDate, String realtimeTaskType) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(MdPriceShfeMapper.class)
                    .deleteRealtimeByDate(tradeDate, realtimeTaskType);
        } catch (Exception e) {
            if (PriceSupport.isClosedDataSource(e)) {
                System.err.println(">>> [Overwrite] 数据源已关闭，跳过实时行清理（应用可能正在重启）");
                return 0;
            }
            System.err.println(">>> [Overwrite] 清理当日实时行失败（不影响收盘数据）: " + e.getMessage());
            return 0;
        }
    }
}
