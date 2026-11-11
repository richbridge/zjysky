package com.zjysky.crawler.processor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zjysky.crawler.config.CrawlerConfig;
import com.zjysky.crawler.model.MdPriceSmm;
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
 * 四大组件之一：PageProcessor（SMM 上海有色网现货历史行情，晚上采集口径）。
 *
 * <p>接口：{@code https://hq.smm.cn/ajax/spot/history/{productId}/{start}/{end}}。</p>
 *
 * <p>和 CJ 一样，实测接口忽略起止日期、固定返回最近约 30 个交易日。
 * 默认 {@code quote-mode=latest}，只落最新报价日那批；
 * {@code task_type} 默认 {@code NIGHT}，与白天的 QUOTE 口径分开。</p>
 *
 * <p>响应里还有 {@code lme_data} / {@code shfe_data} 两个关联数组，
 * 本处理器通过 {@link PriceSupport#linkedProducts} 把里面的合约 id
 * 以 {@code LME:} / {@code SHFE:} 前缀写入 md_source_product，
 * 这样「所有合约 id 信息」不会只停留在原始 JSON 里。</p>
 */
public class SmmProcessor implements PageProcessor {

    private static final String CFG = "crawler.smm.";

    private final Site site = buildSite();
    private final List<MdPriceSmm> rows = new ArrayList<>();

    public List<MdPriceSmm> getRows() {
        return rows;
    }

    private static Site buildSite() {
        return Site.me()
                .setCharset(CrawlerConfig.str(CFG + "site.charset", "UTF-8"))
                .setTimeOut(CrawlerConfig.intVal(CFG + "site.time-out", 20000))
                .setRetryTimes(CrawlerConfig.intVal(CFG + "site.retry-times", 3))
                .setSleepTime(CrawlerConfig.intVal(CFG + "site.sleep-time", 1000))
                .setUserAgent(CrawlerConfig.str(CFG + "site.user-agent", "Mozilla/5.0"))
                .setUseGzip(true)
                .addHeader("Referer", CrawlerConfig.str(CFG + "site.referer", "https://hq.smm.cn/"))
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Accept", "application/json, text/plain, */*");
    }

    @Override
    public void process(Page page) {
        rows.clear();
        String raw = page.getRawText();
        if (raw == null || raw.isBlank()) {
            System.err.println(">>> [SMM] 返回为空: " + page.getUrl());
            page.setSkip(true);
            return;
        }

        JSONObject root;
        try {
            root = JSON.parseObject(raw);
        } catch (Exception e) {
            System.err.println(">>> [SMM] JSON 解析失败: " + e.getMessage());
            page.setSkip(true);
            return;
        }
        if (root == null) {
            page.setSkip(true);
            return;
        }
        String code = PriceSupport.text(root.get("code"));
        if (code != null && !"0".equals(code) && !"200".equals(code)) {
            System.err.println(">>> [SMM] 接口返回异常 code=" + code
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
        if (productId == null) {
            productId = firstProductId(all);
        }
        if (productId == null) {
            productId = CrawlerConfig.str(CFG + "default-product-id", "201102250376");
        }

        String mode = CrawlerConfig.str(CFG + "quote-mode", "latest");
        if (CrawlerConfig.boolVal(CFG + "date-filter", false)) {
            mode = "range";
        }
        List<JSONObject> selected = PriceSupport.selectRows(all, mode, startDate, endDate);
        String taskType = CrawlerConfig.str(CFG + "task-type", "NIGHT");

        for (JSONObject row : selected) {
            String quoteDate = PriceSupport.date(PriceSupport.pick(row,
                    "renew_date", "报价日期", "date"));
            if (quoteDate == null) {
                continue;
            }
            MdPriceSmm d = new MdPriceSmm();
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
            linked = PriceSupport.linkedProducts("SMM", root);
            page.putField(CrawlKeys.LINKED_PRODUCTS, linked);
        }
        page.putField(CrawlKeys.SMM_ROWS, rows);
        page.putField(CrawlKeys.PRODUCT_ID, productId);
        page.putField(CrawlKeys.PRODUCT_NAME, productName);

        if (rows.isEmpty()) {
            if (linked.isEmpty()) {
                System.err.println(">>> [SMM] 本次无有效数据（mode=" + mode
                        + "，接口返回 " + all.size() + " 行，日期参数 "
                        + startDate + " ~ " + endDate + "）");
                page.setSkip(true);
            } else {
                System.out.println(">>> [SMM] 行情 0 行，但发现 " + linked.size() + " 个关联合约 id，仍写入字典表");
            }
            return;
        }
        System.out.println(">>> [SMM] 解析完成: productId=" + productId
                + " 接口返回 " + all.size() + " 行，按 mode=" + mode + " 保留 " + rows.size()
                + " 行，报价日=" + rows.get(0).getQuoteDate()
                + "，task_type=" + taskType);
    }

    private static String extra(Request request, String key) {
        Object v = request == null ? null : request.getExtra(key);
        return v == null ? null : PriceSupport.text(v);
    }

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
