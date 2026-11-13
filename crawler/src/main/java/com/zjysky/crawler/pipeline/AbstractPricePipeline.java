package com.zjysky.crawler.pipeline;

import com.zjysky.crawler.CrawlerConfig;
import com.zjysky.crawler.DbConfig;
import com.zjysky.crawler.PriceSupport;
import com.zjysky.crawler.guard.CloseTimeGuard;
import com.zjysky.common.database.entity.MdSourceProduct;
import com.zjysky.common.database.mapper.MdSourceProductMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import us.codecraft.webmagic.ResultItems;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.pipeline.Pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 四大组件之一：Pipeline 的共用基类，四个源各有一个子类
 * （{@link SgePipeline} / {@link ShfePipeline} / {@link CjPipeline} / {@link SmmPipeline}）。
 *
 * <p>基类集中四件事，子类只保留「自己的表、自己的 Mapper」：</p>
 * <ol>
 *   <li>构造时用 {@link PriceSupport#checkColumns} 对目标表做字段自检（缺列立刻报错）；</li>
 *   <li>{@link #writeProducts} 把合约字典写入 md_source_product（原 ProductPipeline）；</li>
 *   <li>{@link #saveMeta} 统一写采集日志 / 字段字典（写失败绝不影响行情）；</li>
 *   <li>{@link #overwriteRealtime} 收盘数据落库后清理同日 REALTIME 行（原 RealtimeOverwrite）。</li>
 * </ol>
 */
public abstract class AbstractPricePipeline implements Pipeline {

    /** 数据源（大写），如 SGE，用于日志与元信息写入。 */
    protected final String source;
    protected final SqlSessionFactory factory;
    /** 本次写入的目标表。 */
    protected final String table;

    protected AbstractPricePipeline(String source, String tableConfigKey, String defaultTable,
                                    List<String> requiredColumns) {
        this.source = source;
        this.factory = DbConfig.getSqlSessionFactory();
        this.table = PriceSupport.requireSafeTable(
                CrawlerConfig.str(tableConfigKey, defaultTable));
        PriceSupport.checkColumns(factory, table, requiredColumns);
    }

    // ================================================================
    // 合约字典（原 ProductPipeline）
    // ================================================================

    /** md_source_product 字段自检只需做一次（进程内所有 Pipeline 共用）。 */
    private static volatile boolean productSchemaChecked = false;

    /**
     * 把一批合约写入 / 更新到 {@code md_source_product}。
     *
     * <p>按唯一键 (source, product_id) 去重后批量 upsert：重复发现的合约只更新
     * 名称 / 分组，不产生重复行。写入失败只打日志不抛异常——合约字典是辅助数据，
     * 不能因为它影响行情落库的结果。</p>
     *
     * @return 实际写入 / 更新的合约个数（0 也正常）
     */
    protected int writeProducts(List<MdSourceProduct> products) {
        return writeProducts(factory, products);
    }

    /** 静态版本：供 Spider 的品种发现链路直接调用。 */
    public static int writeProducts(SqlSessionFactory factory, List<MdSourceProduct> products) {
        if (products == null || products.isEmpty()) {
            return 0;
        }
        Map<String, MdSourceProduct> unique = new LinkedHashMap<>();
        for (MdSourceProduct p : products) {
            if (p == null || p.getSource() == null || p.getProductId() == null
                    || p.getProductId().isBlank()) {
                continue;
            }
            unique.putIfAbsent(p.getSource() + "|" + p.getProductId(), p);
        }
        if (unique.isEmpty()) {
            return 0;
        }
        ensureProductSchema(factory);
        try (SqlSession session = factory.openSession(true)) {
            int affected = session.getMapper(MdSourceProductMapper.class)
                    .upsertProducts(new ArrayList<>(unique.values()));
            System.out.println(">>> [Product Pipeline] 写入/更新 " + unique.size()
                    + " 个合约到 md_source_product（影响行数 " + affected + "）");
            return unique.size();
        } catch (Exception e) {
            if (PriceSupport.isClosedDataSource(e)) {
                System.err.println(">>> [Product Pipeline] 数据源已关闭，跳过本次写入（应用可能正在重启）");
                return 0;
            }
            // 合约字典失败不影响行情主数据
            System.err.println(">>> [Product Pipeline] 落库失败（不影响行情数据）: " + e.getMessage());
            return 0;
        }
    }

    /** 从 ResultItems 收集品种发现与关联合约（SOURCE_PRODUCTS + LINKED_PRODUCTS）。 */
    public static List<MdSourceProduct> collectProducts(ResultItems resultItems) {
        List<MdSourceProduct> all = new ArrayList<>();
        appendProducts(all, resultItems.get(PriceSupport.SOURCE_PRODUCTS));
        appendProducts(all, resultItems.get(PriceSupport.LINKED_PRODUCTS));
        return all;
    }

    private static void appendProducts(List<MdSourceProduct> target, Object value) {
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof MdSourceProduct p) {
                    target.add(p);
                }
            }
        }
    }

    private static void ensureProductSchema(SqlSessionFactory factory) {
        if (productSchemaChecked) {
            return;
        }
        synchronized (AbstractPricePipeline.class) {
            if (!productSchemaChecked) {
                PriceSupport.checkColumns(factory, "md_source_product", List.of(
                        "source", "product_id", "product_name", "product_group", "enabled"));
                productSchemaChecked = true;
            }
        }
    }

    /** 行情行没带名称时，用合约 id 从字典表反查；失败返回 null（product_name 允许为空）。 */
    protected String fillName(String productId) {
        if (productId == null || productId.isBlank()) {
            return null;
        }
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(MdSourceProductMapper.class)
                    .findProductName(source, productId);
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================
    // 采集元信息
    // ================================================================

    /** 成功 / 失败都记一条 md_crawl_log +（成功时）字段字典；写失败绝不影响行情落库。 */
    protected void saveMeta(String taskType, boolean success, int records, long costMs,
                            String message, List<String> rawJsonList) {
        if (!success) {
            CrawlMetaSupport.saveLog(factory, source, taskType, false, records, costMs, message);
            return;
        }
        if (rawJsonList != null) {
            CrawlMetaSupport.saveFields(factory, source, taskType, rawJsonList);
        }
        CrawlMetaSupport.saveLog(factory, source, taskType, true, records, costMs,
                message == null ? CrawlMetaSupport.message(records, costMs) : message);
    }

    // ================================================================
    // 收盘覆盖实时（原 RealtimeOverwrite）
    // ================================================================

    /**
     * 收盘口径（QUOTE / NIGHT）落库成功后调用：删除同日 REALTIME 行。
     *
     * @param taskType       本次写入的 task_type
     * @param pricedByDate   交易日 → 该日「有价格的记录数」；只有大于 0 的日期才会清理，
     *                       盘中半成品（close_price 全空）不会误删实时数据
     * @return 实际删除的实时行数（0 也正常）
     */
    protected int overwriteRealtime(String taskType, Map<String, Integer> pricedByDate) {
        if (!CloseTimeGuard.overwriteRealtime()) {
            System.out.println(">>> [Overwrite] 已关闭收盘覆盖实时"
                    + "（crawler.market-close.overwrite-realtime=false），跳过");
            return 0;
        }
        // 只有 QUOTE / NIGHT 这种「价格已定稿」的口径才触发清理，
        // 重复采 REALTIME 不会把刚写的实时数据删掉。
        if (!CloseTimeGuard.isCloseTaskType(taskType)) {
            return 0;
        }
        if (pricedByDate == null || pricedByDate.isEmpty()) {
            return 0;
        }
        Set<String> dates = new LinkedHashSet<>();
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<String, Integer> e : pricedByDate.entrySet()) {
            String normalized = PriceSupport.date(e.getKey());
            if (normalized == null) {
                continue;
            }
            if (e.getValue() == null || e.getValue() <= 0) {
                skipped.add(normalized);
                continue;
            }
            dates.add(normalized);
        }
        if (!skipped.isEmpty()) {
            System.out.println(">>> [Overwrite] " + source + " 以下交易日收盘数据还没有价格，"
                    + "判定为未定稿，保留实时行不删: " + skipped);
        }
        if (dates.isEmpty()) {
            return 0;
        }

        String realtimeType = CloseTimeGuard.realtimeTaskType();
        int deleted = 0;
        for (String date : dates) {
            deleted += deleteRealtimeByDate(date, realtimeType);
        }
        if (deleted > 0) {
            System.out.println(">>> [Overwrite] " + source + " 收盘(" + taskType + ")落库成功，"
                    + "已删除同日实时(" + realtimeType + ") " + deleted + " 行，日期=" + dates
                    + "，表=" + table);
        } else {
            System.out.println(">>> [Overwrite] " + source + " 收盘(" + taskType + ")落库成功，"
                    + dates + " 没有需要清理的实时行（本次之前未采过实时）");
        }
        return deleted;
    }

    /**
     * 删除本表某交易日的 REALTIME 行；删除失败只打日志、不抛异常
     * （行情主数据已经写成功，不能因为清理失败把整次采集标记失败）。
     * 由各源子类用自己的 Mapper 实现；没有实时口径的源返回 0 即可。
     */
    protected abstract int deleteRealtimeByDate(String tradeDate, String realtimeTaskType);
}
