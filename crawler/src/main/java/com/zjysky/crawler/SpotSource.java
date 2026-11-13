package com.zjysky.crawler;

import com.zjysky.common.database.entity.MdPriceCj;
import com.zjysky.common.database.entity.MdPriceSmm;
import com.zjysky.common.database.row.SpotPrice;

import java.util.List;
import java.util.Locale;

/**
 * 现货报价源（CJ 长江有色 / SMM 上海有色网）的差异表。
 *
 * <p><b>为什么两者能共用一套代码</b>：{@code cj.smm.cn} 和 {@code hq.smm.cn}
 * 是同一个平台的两个站，行情接口完全一样——
 * {@code /ajax/spot/history/{productId}/{start}/{end}}，返回同一套 JSON 字段
 * （{@code renew_date / average / highs / low / vchange / vchange_rate / point_precision}），
 * 落库的三张表列结构也一致。差别只有下面这几项：域名、合约目录页长什么样、
 * task_type 默认值、默认日期。</p>
 *
 * <p>把这些差异集中在这个 record 里，Processor / Pipeline / Spider 各只有一份实现。
 * 新增同平台站点只需在这里加一个常量。</p>
 *
 * @param name             大写源名，用于日志与 {@code md_source_product.source}
 * @param baseUrl          站点根地址
 * @param discovery        品种发现页的解析方式
 * @param taskType         默认 task_type（CJ 写 QUOTE，SMM 写 NIGHT，两者不互相覆盖）
 * @param defaultStartDate 未指定日期时的默认起始日
 * @param defaultEndDate   未指定日期时的默认结束日
 * @param defaultProductId 未指定合约时的兜底合约 id
 * @param discoveryThreads 品种发现页的并发线程数
 * @param intervalMillis   逐合约采集之间的间隔（毫秒），防限流
 */
public record SpotSource(String name, String baseUrl, Discovery discovery, String taskType,
                         String defaultStartDate, String defaultEndDate, String defaultProductId,
                         int discoveryThreads, long intervalMillis) {

    /** 品种发现页的解析方式。 */
    public enum Discovery {
        /** CJ：首页按板块罗列，条目是 {@code <a href="/category/{id}">}。 */
        HREF_CATEGORY,
        /** SMM：合约分散在多个专区页，条目是 {@code <option value="category_{id}">}。 */
        OPTION_CATEGORY
    }

    /** 长江有色：白天口径，与 SMM 的 NIGHT 分开存，互不覆盖。 */
    public static final SpotSource CJ = new SpotSource(
            "CJ", "https://cj.smm.cn", Discovery.HREF_CATEGORY, "QUOTE",
            "2025-10-06", "2025-11-06", "201102250293", 1, 400L);

    /** 上海有色网：晚上采集口径，合约多（260+），间隔放大、发现页并发提高。 */
    public static final SpotSource SMM = new SpotSource(
            "SMM", "https://hq.smm.cn", Discovery.OPTION_CATEGORY, "NIGHT",
            "2025-11-19", "2025-11-19", "201102250376", 4, 800L);

    private static final List<SpotSource> ALL = List.of(CJ, SMM);

    /** 全部现货源。 */
    public static List<SpotSource> all() {
        return ALL;
    }

    /** 按源名取，大小写不敏感。 */
    public static SpotSource of(String name) {
        return ALL.stream()
                .filter(s -> s.name.equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "未知现货源 " + name + "，可用 CJ / SMM"));
    }

    /** 配置前缀，如 crawler.cj. */
    public String cfg() {
        return "crawler." + name.toLowerCase(Locale.ROOT) + ".";
    }

    /** 落库表名，默认 md_price_cj / md_price_smm。 */
    public String table() {
        return CrawlerConfig.str(cfg() + "table", "md_price_" + name.toLowerCase(Locale.ROOT));
    }

    /** 行情接口，形如 {@code {baseUrl}/ajax/spot/history/{id}/{start}/{end}}。 */
    public String historyUrl(String productId, String startDate, String endDate) {
        String template = CrawlerConfig.str(cfg() + "history-url-template",
                baseUrl + "/ajax/spot/history/%s/%s/%s");
        return String.format(template, productId, startDate, endDate);
    }

    /**
     * 新建一行本源的行情实体（Processor 解析时用）。
     * CJ → {@link MdPriceCj}，SMM → {@link MdPriceSmm}，两者都实现 {@link SpotPrice}，
     * 因此解析代码只有一份，落库时由各源 Pipeline 分派到自己的 Mapper。
     */
    public SpotPrice newRow() {
        return "CJ".equals(name) ? new MdPriceCj() : new MdPriceSmm();
    }

    /** 品种发现入口，默认站点首页。 */
    public String homeUrl() {
        return CrawlerConfig.str(cfg() + "home-url", baseUrl + "/");
    }
}
