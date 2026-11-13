package com.zjysky.crawler.processor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zjysky.crawler.CrawlerConfig;
import com.zjysky.crawler.PriceSupport;
import com.zjysky.crawler.SpotSource;
import com.zjysky.common.database.entity.MdSourceProduct;
import com.zjysky.common.database.row.SpotPrice;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.processor.PageProcessor;

import java.util.ArrayList;
import java.util.List;

/**
 * 现货源（CJ / SMM）的 PageProcessor 共用基类，一个源一个子类
 * （{@link CjProcessor} / {@link SmmProcessor}）。
 *
 * <p>两类页面按 URL 自动分派：</p>
 * <ul>
 *   <li><b>行情历史</b> {@code /ajax/spot/history/{productId}/{start}/{end}} → 解析报价行；</li>
 *   <li><b>品种发现页</b>（首页 / 专区页）→ 解析合约 id，交给子类实现。</li>
 * </ul>
 *
 * <p>两个站点返回同一套 JSON 字段（renew_date / average / highs / low /
 * vchange / vchange_rate / point_precision），差异只在配置前缀与目录页形态，
 * 由 {@link SpotSource} 与子类提供。</p>
 *
 * <p><b>重要实测结论</b>：历史接口会忽略 start / end，固定返回最近约 30 个交易日。
 * 因此默认 {@code quote-mode=latest}，只落响应中报价日期最新的那批，
 * 避免把「请求 2025 年、实际返回 2026 年」的数据当成目标日期写错。</p>
 *
 * <p>可选模式（{@code crawler.&lt;源&gt;.quote-mode}）：latest（默认）/ all / range。</p>
 */
public abstract class AbstractSpotProcessor implements PageProcessor {

    protected final SpotSource source;
    private final Site site;
    /** 本次解析出的行情行；Spider 每次只跑一个请求，所以这里可以安全复用。 */
    private final List<SpotPrice> rows = new ArrayList<>();

    protected AbstractSpotProcessor(SpotSource source) {
        this.source = source;
        this.site = buildSite();
    }

    /** 本源名（大写），如 CJ。 */
    public final String name() {
        return source.name();
    }

    public final List<SpotPrice> getRows() {
        return rows;
    }

    // ================================================================
    // 子类需要实现的差异点：品种发现
    // ================================================================

    /** 品种发现的起始请求（CJ 只有首页一个入口；SMM 是 25 个专区页）。 */
    public abstract List<Request> discoveryRequests();

    /** 解析品种发现页，把 {@code sourceProducts} 字段放进 ResultItems。 */
    protected abstract void parseProducts(Page page, String raw, String defaultGroup);

    // ================================================================
    // URL 分派 + 行情解析
    // ================================================================

    @Override
    public void process(Page page) {
        String raw = page.getRawText();
        if (raw == null || raw.isBlank()) {
            skip(page, "返回为空: " + page.getUrl());
            return;
        }
        String url = page.getUrl() == null ? "" : page.getUrl().get();
        if (url.contains("/ajax/spot/history/")) {
            parseQuote(page, raw);
        } else {
            Object extra = page.getRequest() == null ? null
                    : page.getRequest().getExtra(PriceSupport.PRODUCT_GROUP);
            String defaultGroup = extra == null ? source.name() + "现货" : String.valueOf(extra);
            parseProducts(page, raw, defaultGroup);
        }
    }

    /**
     * 行情历史 JSON → 报价行。解析出的行实体由 {@link SpotSource#newRow()} 按源创建，
     * CJ 落 MdPriceCj、SMM 落 MdPriceSmm，两者共用同一套字段映射。
     */
    private void parseQuote(Page page, String raw) {
        rows.clear();
        String tag = "[" + source.name() + "]";
        JSONObject root;
        try {
            root = JSON.parseObject(raw);
        } catch (Exception e) {
            skip(page, tag, "JSON 解析失败: " + e.getMessage());
            return;
        }
        if (root == null) {
            page.setSkip(true);
            return;
        }
        String code = PriceSupport.text(root.get("code"));
        if (code != null && !"0".equals(code) && !"200".equals(code)) {
            skip(page, tag, "接口返回异常 code=" + code
                    + " msg=" + PriceSupport.text(root.get("msg")));
            return;
        }

        Request request = page.getRequest();
        String productId = extra(request, PriceSupport.PRODUCT_ID);
        String productName = extra(request, PriceSupport.PRODUCT_NAME);
        String startDate = extra(request, PriceSupport.START_DATE);
        String endDate = extra(request, PriceSupport.END_DATE);

        JSONArray all = PriceSupport.historyRows(root);
        if (productId == null) {
            productId = firstProductId(all);
        }
        if (productId == null) {
            productId = source.defaultProductId();
        }

        String cfg = source.cfg();
        String mode = CrawlerConfig.str(cfg + "quote-mode", "latest");
        if (CrawlerConfig.boolVal(cfg + "date-filter", false)) {
            mode = "range";
        }
        List<JSONObject> selected = PriceSupport.selectRows(all, mode, startDate, endDate);
        String taskType = CrawlerConfig.str(cfg + "task-type", source.taskType());

        for (JSONObject row : selected) {
            String quoteDate = PriceSupport.date(PriceSupport.pick(row,
                    "renew_date", "报价日期", "date"));
            if (quoteDate == null) {
                continue; // 唯一键含 quote_date，NULL 会让唯一约束失效，直接跳过
            }
            SpotPrice d = source.newRow();
            d.setTaskType(taskType);
            String rowProductId = PriceSupport.pick(row, "product_id", "productId", "品种代码");
            d.setProductId(rowProductId == null ? productId : rowProductId);
            d.setProductName(productName);
            d.setQuoteDate(PriceSupport.localDate(quoteDate));
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
        if (CrawlerConfig.boolVal(cfg + "include-linked-ids", true)) {
            linked = PriceSupport.linkedProducts(source.name(), root);
            page.putField(PriceSupport.LINKED_PRODUCTS, linked);
        }
        page.putField(PriceSupport.SPOT_ROWS, rows);
        page.putField(PriceSupport.PRODUCT_ID, productId);
        page.putField(PriceSupport.PRODUCT_NAME, productName);

        if (rows.isEmpty()) {
            if (linked.isEmpty()) {
                skip(page, tag, "本次无有效数据（mode=" + mode + "，接口返回 " + all.size()
                        + " 行，日期参数 " + startDate + " ~ " + endDate + "）");
            } else {
                System.out.println(">>> " + tag + " 行情 0 行，但发现 " + linked.size()
                        + " 个关联合约 id，仍写入字典表");
            }
        }
    }

    // ================================================================
    // 共用小工具
    // ================================================================

    protected final Site buildSite() {
        String cfg = source.cfg() + "site.";
        return Site.me()
                .setCharset(CrawlerConfig.str(cfg + "charset", "UTF-8"))
                .setTimeOut(CrawlerConfig.intVal(cfg + "time-out", 20000))
                .setRetryTimes(CrawlerConfig.intVal(cfg + "retry-times", 3))
                .setSleepTime(CrawlerConfig.intVal(cfg + "sleep-time", 800))
                .setUserAgent(CrawlerConfig.str(cfg + "user-agent", "Mozilla/5.0"))
                .setUseGzip(true)
                .addHeader("Referer", CrawlerConfig.str(cfg + "referer", source.baseUrl() + "/"))
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Accept", "application/json, text/plain, */*");
    }

    protected final void skip(Page page, String message) {
        System.err.println(">>> [" + source.name() + "] " + message);
        page.setSkip(true);
    }

    /** 带来源标签的跳过提示（行情解析分支用）。 */
    protected final void skip(Page page, String tag, String message) {
        System.err.println(">>> " + tag + " " + message);
        page.setSkip(true);
    }

    /** 从 Request extra 里取字符串；WebMagic 的 extra 可以是任意对象。 */
    protected final String extra(Request request, String key) {
        Object v = request == null ? null : request.getExtra(key);
        return v == null ? null : PriceSupport.text(v);
    }

    /** 当请求没带 productId 时，从第一行数据里兜底取一个。 */
    protected final String firstProductId(JSONArray rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        JSONObject first = rows.getJSONObject(0);
        return first == null ? null : PriceSupport.pick(first, "product_id", "productId");
    }

    /** 取元素纯文本（去标签、压缩空白），品种发现页解析用。 */
    protected static String textOf(us.codecraft.webmagic.selector.Selectable el) {
        String t = el == null ? null : el.get();
        if (t == null) {
            return "";
        }
        return t.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    /** 去掉下拉框文案里常见的 HTML 实体。 */
    protected static String cleanText(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ").trim();
    }

    /** 组装一个 MdSourceProduct（品种发现统一出口）。 */
    protected final MdSourceProduct newProduct(String productId, String name, String group) {
        MdSourceProduct p = new MdSourceProduct();
        p.setSource(source.name());
        p.setProductId(productId);
        p.setProductName(name);
        p.setProductGroup(group);
        p.setEnabled(1);
        return p;
    }

    @Override
    public Site getSite() {
        return site;
    }
}
