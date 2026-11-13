package com.zjysky.crawler.pipeline;

import com.zjysky.common.database.mapper.MdPriceSgeMapper;
import com.zjysky.common.database.entity.MdPriceSge;
import com.zjysky.common.database.entity.MdSourceProduct;
import com.zjysky.crawler.guard.CloseTimeGuard;
import com.zjysky.crawler.PriceSupport;
import org.apache.ibatis.session.SqlSession;
import us.codecraft.webmagic.ResultItems;
import us.codecraft.webmagic.Task;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四大组件之一：Pipeline（SGE 上金所 → md_price_sge）。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>构造时检查 md_price_sge 的 18 个必要列是否齐全；</li>
 *   <li>从 ResultItems 里取出 sgeList；</li>
 *   <li>通过 {@link MdPriceSgeMapper#upsertBatch} 批量 upsert，重复采集幂等；</li>
 *   <li>收盘数据落库后清理同日 REALTIME 行，打印采集摘要。</li>
 * </ol>
 */
public class SgePipeline extends AbstractPricePipeline {

    /** 本次 INSERT 用到的全部业务列，缺一不可。 */
    private static final List<String> REQUIRED = List.of(
            "task_type", "trade_date", "contract", "seq_no",
            "open_price", "high_price", "low_price", "close_price", "settle_price", "avg_price",
            "change_value", "change_pct", "volume", "open_interest",
            "delivery_direction", "delivery_volume", "turnover", "raw_json");

    public SgePipeline() {
        super("SGE", "crawler.sge.table", "md_price_sge", REQUIRED);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(ResultItems resultItems, Task task) {
        long begin = System.currentTimeMillis();
        List<MdPriceSge> list = resultItems.get("sgeList");
        if (list == null || list.isEmpty()) {
            return;
        }
        String taskType = list.get(0).getTaskType();

        // 收盘 Excel 必须是「价格已定稿」的一天。上金所收盘 Excel 只有收盘价、没有结算价，
        // 所以这里用「有收盘价的记录过半」来判定；不过半就认为拿到的不是正式收盘数据，
        // 不写库、也不删实时行。
        if (CloseTimeGuard.isCloseTaskType(taskType)) {
            int priced = (int) list.stream().filter(d -> d.getClosePrice() != null).count();
            if (!CloseTimeGuard.finalizedByRatio(list.size(), priced)) {
                String msg = "跳过：收盘数据尚未定稿（"
                        + CloseTimeGuard.describeFinalized(list.size(), priced, 0) + "）";
                System.out.println(">>> [SGE Pipeline] " + list.get(0).getTradeDate() + " " + msg);
                saveMeta(taskType, false, 0, System.currentTimeMillis() - begin, msg, null);
                return;
            }
        }

        int affected;
        try {
            affected = upsert(list);
            LocalDate date = list.get(0).getTradeDate();
            System.out.println(">>> [SGE Pipeline] 落库完成: " + table + " " + date
                    + " 共 " + list.size() + " 条（影响行数 " + affected + "）");
        } catch (Exception e) {
            if (PriceSupport.isClosedDataSource(e)) {
                System.err.println(">>> [SGE Pipeline] 数据源已关闭，跳过本次写入（应用可能正在重启）");
                return;
            }
            System.err.println(">>> [SGE Pipeline] 落库失败: " + e.getMessage());
            saveMeta(taskType, false, 0, System.currentTimeMillis() - begin,
                    "SGE 落库失败: " + e.getMessage(), null);
            throw new IllegalStateException(e);
        }

        // 需求「合约信息采集价格时顺便更新完善」：SGE 表没有品种列，
        // 直接用 contract（Au99.99 / Au(T+D)…）作为合约 id 写入 md_source_product；
        // 上金所全部是贵金属合约，分组固定「贵金属」。写入失败不影响行情落库。
        List<MdSourceProduct> products = new ArrayList<>();
        java.util.Set<String> seenContracts = new java.util.HashSet<>();
        for (MdPriceSge d : list) {
            if (d.getContract() == null || d.getContract().isBlank()
                    || !seenContracts.add(d.getContract())) {
                continue;
            }
            MdSourceProduct p = new MdSourceProduct();
            p.setSource("SGE");
            p.setProductId(d.getContract());
            p.setProductName(d.getContract());
            p.setProductGroup("贵金属");
            p.setEnabled(1);
            products.add(p);
        }
        writeProducts(products);

        // 采集元信息：字段字典 + 成功日志（写失败绝不影响上面的行情落库）
        long costMs = System.currentTimeMillis() - begin;
        saveMeta(taskType, true, list.size(), costMs, null,
                list.stream().map(MdPriceSge::getRawJson).toList());

        // 收盘 Excel（QUOTE）落库成功后，删掉同日延时行情写入的 REALTIME 行。
        // 只清理「本次确实写到了收盘价」的交易日，避免盘中半成品删掉实时数据。
        Map<String, Integer> pricedByDate = new LinkedHashMap<>();
        for (MdPriceSge d : list) {
            if (d.getClosePrice() != null) {
                pricedByDate.merge(d.getTradeDate().toString(), 1, Integer::sum);
            }
        }
        overwriteRealtime(taskType, pricedByDate);

        Map<String, Integer> byContract = new LinkedHashMap<>();
        list.forEach(d -> byContract.merge(d.getContract(), 1, Integer::sum));
        int sampleSize = com.zjysky.crawler.CrawlerConfig.intVal("crawler.sge.sample-size", 5);
        System.out.println("=== SGE " + list.get(0).getTradeDate()
                + " 共 " + list.size() + " 条，合约样例 " + byContract + " ===");
        list.stream().limit(sampleSize).forEach(System.out::println);
        if (list.size() > sampleSize) {
            System.out.println("…… 其余 " + (list.size() - sampleSize) + " 条已落库");
        }
    }

    private int upsert(List<MdPriceSge> list) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(MdPriceSgeMapper.class).upsertBatch(list);
        }
    }

    /** 收盘定稿后删除同日 REALTIME 行（crawler.sge.realtime.task-type）。 */
    @Override
    protected int deleteRealtimeByDate(String tradeDate, String realtimeTaskType) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(MdPriceSgeMapper.class)
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
