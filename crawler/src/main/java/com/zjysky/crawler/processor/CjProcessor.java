package com.zjysky.crawler.processor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zjysky.crawler.config.CrawlerConfig;
import com.zjysky.crawler.model.MdPriceCj;
import com.zjysky.crawler.model.MdSourceProduct;
import com.zjysky.crawler.support.CrawlKeys;
import com.zjysky.crawler.support.PriceSupport;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.processor.PageProcessor;

import java.util.ArrayList;
import java.util.List;

/**
 * 四大组件之一：PageProcessor（CJ 长江有色现货历史行情）。
 *
 * <p>接口：{@code https://cj.smm.cn/ajax/spot/history/{productId}/{start}/{end}}。</p>
 *
 * <p><b>重要实测结论</b>：该接口会忽略 start / end，固定返回最近约 30 个交易日。
 * 因此默认 {@code quote-mode=latest}，只落响应中报价日期最新的那批，
 * 避免把「请求 2025 年、实际返回 2026 年」的数据当成目标日期写错。</p>
 *
 * <p>可选模式（application.yml 的 {@code crawler.cj.quote-mode}）：</p>
 * <ul>
 *   <li>{@code latest} — 只落最新报价日（默认，适合每日/晚上采集）；</li>
 *   <li>{@code all} — 30 个交易日全落（首次补历史）；</li>
 *   <li>{@code range} — 严格按 start/end 过滤（可能 0 条，因为接口不认这两个参数）。</li>
 * </ul>
 *
 * <p>同时会把响应里 {@code lme_list} / {@code shfe_list} 的关联合约 id
 * 放进 {@code linkedProducts}，交给 Pipeline 写入 md_source_product。</p>
 */
public class CjProcessor implements PageProcessor {

    private static final String CFG = "crawler.cj.";

    private final Site site = buildSite();
    /** 本次解析出的行情行；Spider 每次只跑一个请求，所以这里可以安全复用。 */
    private final List<MdPriceCj> rows = new ArrayList<>();

    public List<MdPriceCj> getRows() {
        return rows;
    }

    private static Site buildSite() {
        return Site.me()
                .setCharset(CrawlerConfig.str(CFG + "site.charset", "UTF-8"))
                .setTimeOut(CrawlerConfig.intVal(CFG + "site.time-out", 20000))
                .setRetryTimes(CrawlerConfig.intVal(CFG + "site.retry-times", 3))
                .setSleepTime(CrawlerConfig.intVal(CFG + "site.sleep-time", 800))
                .setUserAgent(CrawlerConfig.str(CFG + "site.user-agent", "Mozilla/5.0"))
                .setUseGzip(true)
                .addHeader("Referer", CrawlerConfig.str(CFG + "site.referer", "https://cj.smm.cn/"))
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Accept", "application/json, text/plain, */*");
    }

    @Override
    public void process(Page page) {
        rows.clear();
        String raw = page.getRawText();
        if (raw == null || raw.isBlank()) {
            System.err.println(">>> [CJ] 返回为空: " + page.getUrl());
            page.setSkip(true);
            return;
        }

        JSONObject root;
        try {
            root = JSON.parseObject(raw);
        } catch (Exception e) {
            System.err.println(">>> [CJ] JSON 解析失败: " + e.getMessage());
            page.setSkip(true);
            return;
        }
        if (root == null) {
            page.setSkip(true);
            return;
        }
        String code = PriceSupport.text(root.get("code"));
        if (code != null && !"0".equals(code) && !"200".equals(code)) {
            System.err.println(">>> [CJ] 接口返回异常 code=" + code
                    + " msg=" + PriceSupport.text(root.get("msg")));
            page.setSkip(true);
            return;
        }

        Request request = page.getRequest();
        String productId = extra(request, CrawlKeys.PRODUCT_ID);
        String productName = extra(request, CrawlKeys.PRODUCT_NAME);
        String startDate = extra(request, CrawlKeys.START_DATE);
        String endDate = extra(request, CrawlKeys.END_DATE);

        JSONArray all = PriceSupport.historyRows(root);
        if (all.isEmpty() && productId == null) {
            productId = firstProductId(all);
        }
        if (productId == null) {
            productId = CrawlerConfig.str(CFG + "default-product-id", "201102250293");
        }

        String mode = CrawlerConfig.str(CFG + "quote-mode", "latest");
        if (CrawlerConfig.boolVal(CFG + "date-filter", false)) {
            mode = "range";
        }
        List<JSONObject> selected = PriceSupport.selectRows(all, mode, startDate, endDate);
        String taskType = CrawlerConfig.str(CFG + "task-type", "QUOTE");

        for (JSONObject row : selected) {
            String quoteDate = PriceSupport.date(PriceSupport.pick(row,
                    "renew_date", "报价日期", "date"));
            if (quoteDate == null) {
                continue; // 唯一键含 quote_date，NULL 会让 MySQL 唯一约束失效，直接跳过
            }
            MdPriceCj d = new MdPriceCj();
            d.setTaskType(taskType);
            String rowProductId = PriceSupport.pick(row, "product_id", "productId", "品种代码");
            d.setProductId(rowProductId == null ? productId : rowProductId);
            d.setProductName(productName);
            d.setQuoteDate(quoteDate);
            d.setAvgPrice(PriceSupport.decimal(PriceSupport.pick(row, "average", "均价", "average_show")));
            d.setHighPrice(PriceSupport.decimal(PriceSupport.pick(row, "highs", "最高价", "high_show")));
            d.setLowPrice(PriceSupport.decimal(PriceSupport.pick(row, "low", "最低价", "low_show")));
            d.setChangeValue(PriceSupport.decimal(PriceSupport.pick(row,
                    "vchange", "涨跌额", "change_value_show")));
            d.setChangePct(PriceSupport.percent(PriceSupport.pick(row,
                    "vchange_rate", "涨跌幅", "change_rate_show")));
            d.setPointPrecision(PriceSupport.intValue(PriceSupport.pick(row, "point_precision", "价格精度")));
            d.setRawJson(row.toJSONString());
            rows.add(d);
        }

        List<MdSourceProduct> linked = List.of();
        if (CrawlerConfig.boolVal(CFG + "include-linked-ids", true)) {
            linked = PriceSupport.linkedProducts("CJ", root);
            page.putField(CrawlKeys.LINKED_PRODUCTS, linked);
        }
        page.putField(CrawlKeys.CJ_ROWS, rows);
        page.putField(CrawlKeys.PRODUCT_ID, productId);
        page.putField(CrawlKeys.PRODUCT_NAME, productName);

        if (rows.isEmpty()) {
            if (linked.isEmpty()) {
                System.err.println(">>> [CJ] 本次无有效数据（mode=" + mode
                        + "，接口返回 " + all.size() + " 行，日期参数 "
                        + startDate + " ~ " + endDate + "）");
                page.setSkip(true);
            } else {
                System.out.println(">>> [CJ] 行情 0 行，但发现 " + linked.size() + " 个关联合约 id，仍写入字典表");
            }
            return;
        }
        System.out.println(">>> [CJ] 解析完成: productId=" + productId
                + " 接口返回 " + all.size() + " 行，按 mode=" + mode + " 保留 " + rows.size()
                + " 行，报价日=" + rows.get(0).getQuoteDate());
    }

    /** 从 Request extra 里取字符串；WebMagic 的 extra 可以是任意对象。 */
    private static String extra(Request request, String key) {
        Object v = request == null ? null : request.getExtra(key);
        return v == null ? null : PriceSupport.text(v);
    }

    /** 当请求没带 productId 时，从第一行数据里兜底取一个。 */
    private static String firstProductId(JSONArray rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        JSONObject first = rows.getJSONObject(0);
        return first == null ? null : PriceSupport.pick(first, "product_id", "productId");
    }

    @Override
    public Site getSite() {
        return site;
    }
}
