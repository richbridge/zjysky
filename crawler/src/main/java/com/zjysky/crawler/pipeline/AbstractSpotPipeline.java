package com.zjysky.crawler.pipeline;

import com.zjysky.crawler.CrawlerConfig;
import com.zjysky.crawler.PriceSupport;
import com.zjysky.common.database.row.SpotPrice;
import us.codecraft.webmagic.ResultItems;
import us.codecraft.webmagic.Task;

import java.util.List;

/**
 * 现货源（CJ / SMM）的 Pipeline 共用基类：报价行落库流程只有一份，
 * 子类只绑定自己的表和 Mapper。
 *
 * <p>处理顺序：</p>
 * <ol>
 *   <li>先写本次响应里的合约字典（品种发现 + LME / SHFE 关联合约 id）；</li>
 *   <li>行情行缺合约名称时用 product_id 从 md_source_product 反查；</li>
 *   <li>通过子类 {@link #upsertBatch} 批量 upsert 到本源行情表；</li>
 *   <li>记录字段字典与采集日志；打印样例行。</li>
 * </ol>
 */
public abstract class AbstractSpotPipeline extends AbstractPricePipeline {

    protected AbstractSpotPipeline(String source, String tableConfigKey, String defaultTable) {
        // 现货表 11 个业务列，缺一不可
        super(source, tableConfigKey, defaultTable, List.of(
                "task_type", "product_id", "product_name", "quote_date",
                "avg_price", "high_price", "low_price", "change_value", "change_pct",
                "point_precision", "raw_json"));
    }

    /** 批量 upsert 到本源行情表，唯一键 (task_type, quote_date, product_id)。 */
    protected abstract int upsertBatch(List<? extends SpotPrice> list);

    @Override
    public void process(ResultItems resultItems, Task task) {
        long begin = System.currentTimeMillis();
        // 合约 id 信息先落字典表（品种发现 + 关联合约）；失败不影响行情
        writeProducts(collectProducts(resultItems));

        List<? extends SpotPrice> list = resultItems.get(PriceSupport.SPOT_ROWS);
        if (list == null || list.isEmpty()) {
            return;
        }
        String taskType = list.get(0).getTaskType();
        String productId = resultItems.get(PriceSupport.PRODUCT_ID);
        String productName = resultItems.get(PriceSupport.PRODUCT_NAME);
        if (productName == null || productName.isBlank()) {
            productName = fillName(productId);
        }

        for (SpotPrice d : list) {
            if (d.getProductId() == null || d.getProductId().isBlank()) {
                d.setProductId(productId);
            }
            if ((d.getProductName() == null || d.getProductName().isBlank()) && productName != null) {
                d.setProductName(productName);
            }
        }

        int affected;
        try {
            affected = upsertBatch(list);
        } catch (Exception e) {
            if (PriceSupport.isClosedDataSource(e)) {
                System.err.println(">>> [" + source + " Pipeline] 数据源已关闭，跳过本次写入（应用可能正在重启）");
                return;
            }
            System.err.println(">>> [" + source + " Pipeline] 落库失败: " + e.getMessage());
            saveMeta(taskType, false, 0, System.currentTimeMillis() - begin,
                    source + " 落库失败: " + e.getMessage(), null);
            throw new IllegalStateException(e);
        }
        System.out.println(">>> [" + source + " Pipeline] 落库完成: " + table
                + " productId=" + productId + " 共 " + list.size()
                + " 条（影响行数 " + affected + "）");

        // 采集元信息：字段字典 + 成功日志（写失败绝不影响上面的行情落库）
        long costMs = System.currentTimeMillis() - begin;
        saveMeta(taskType, true, list.size(), costMs, null,
                list.stream().map(SpotPrice::getRawJson).toList());

        int sampleSize = CrawlerConfig.intVal(
                "crawler." + source.toLowerCase() + ".sample-size", 5);
        System.out.println("=== " + source + " " + productId + " / "
                + list.get(0).getQuoteDate() + " 共 " + list.size() + " 条，样例 ===");
        list.stream().limit(sampleSize).forEach(System.out::println);
        if (list.size() > sampleSize) {
            System.out.println("…… 其余 " + (list.size() - sampleSize) + " 条已落库");
        }
    }
}
