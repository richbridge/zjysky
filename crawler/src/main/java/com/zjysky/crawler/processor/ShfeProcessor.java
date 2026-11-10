package com.zjysky.crawler.processor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zjysky.crawler.config.CrawlerConfig;
import com.zjysky.crawler.downloader.ShfeDownloader;
import com.zjysky.crawler.model.ShfeDailyData;
import com.zjysky.crawler.pipeline.ShfePipeline;
import com.zjysky.crawler.scheduler.ShfeScheduler;

import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.Spider;
import us.codecraft.webmagic.processor.PageProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 四大组件之一：PageProcessor。
 *
 * <p>把 ShfeDownloader 下载的每日 JSON 完整映射到统一宽表 {@code md_price_shfe}。
 * 一份 SHFE 报文含三类行情，按表的 row_type 分别落库：</p>
 * <ul>
 *   <li>{@code o_curinstrument} → CONTRACT（328 条，含 1 条"总计"记 TOTAL）</li>
 *   <li>{@code o_curproduct}    → PRODUCT（26 条，含 avg_price / year_volume / year_turnover）</li>
 *   <li>{@code o_curmetalindex} → INDEX（1 条，含 last_price / pre_close_price）</li>
 * </ul>
 * <p>表为 NOT NULL 的 task_type / symbol 在此补齐；原始行 JSON 全字段存入 raw_json，
 * 未被表列覆盖的字段（如指数 UPDOWN1）不会丢失；顶层元数据由 Downloader 落盘的完整 JSON 保留。</p>
 */
public class ShfeProcessor implements PageProcessor {

    private static final String CFG = "crawler.shfe.";
    private static final Pattern DATE_PATTERN = Pattern.compile("kx(\\d{8})");
    private static final String TASK_QUOTE = "QUOTE";

    private final Site site = buildSite();

    private static Site buildSite() {
        return Site.me()
                .setRetryTimes(CrawlerConfig.intVal(CFG + "site.retry-times", 3))
                .setSleepTime(CrawlerConfig.intVal(CFG + "site.sleep-time", 1000))
                .setTimeOut(CrawlerConfig.intVal(CFG + "site.time-out", 15000))
                .setCharset(CrawlerConfig.str(CFG + "site.charset", "UTF-8"))
                .setUserAgent(CrawlerConfig.str(CFG + "site.user-agent", "Mozilla/5.0"))
                .addHeader("Referer", CrawlerConfig.str(CFG + "site.referer", "https://www.shfe.com.cn/"));
    }

    @Override
    public void process(Page page) {
        String raw = page.getRawText();
        if (raw == null || raw.isBlank()) {
            page.setSkip(true);
            return;
        }

        JSONObject root;
        try {
            root = JSON.parseObject(raw);
        } catch (Exception e) {
            page.setSkip(true);
            return;
        }

        String yyyymmdd = extractDate(page.getUrl().get());
        if (yyyymmdd == null || yyyymmdd.isBlank()) {
            yyyymmdd = str(root, "report_date");
        }
        String tradeDate = toIsoDate(yyyymmdd);
        String updateTime = str(root, "update_date");
        String indexSymbol = CrawlerConfig.str(CFG + "index-symbol", "SHFE金属指数");

        List<ShfeDailyData> list = new ArrayList<>(360);
        parseInstruments(root.getJSONArray("o_curinstrument"), tradeDate, updateTime, list);
        parseProducts(root.getJSONArray("o_curproduct"), tradeDate, updateTime, list);
        parseMetalIndex(root.getJSONArray("o_curmetalindex"), tradeDate, updateTime, indexSymbol, list);

        if (list.isEmpty()) {
            page.setSkip(true);
            return;
        }
        page.putField("shfeList", list);
    }

    /** 合约行情：18 个原始字段 → 表列；"总计"行价格字段多为空串，统一转 null */
    private void parseInstruments(JSONArray array, String tradeDate, String updateTime,
                                  List<ShfeDailyData> out) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            String productName = str(item, "PRODUCTNAME");
            String productId = str(item, "PRODUCTID");
            String month = str(item, "DELIVERYMONTH");
            boolean total = "总计".equals(productName) || "总计".equals(productId);
            // o_curproduct 也有一条"总计"行（symbol=总计），两者必须区分，否则撞唯一键
            String symbol = total
                    ? CrawlerConfig.str(CFG + "total-contract-symbol", "总计-合约")
                    : (month == null ? productName : productName + "-" + month);

            ShfeDailyData d = base(tradeDate, updateTime, total ? "TOTAL" : "CONTRACT", symbol, item);
            d.setContractMonth(month);
            d.setProductName(productName);
            d.setProductId(productId);
            d.setProductGroup(str(item, "PRODUCTGROUPID"));
            d.setProductClass(str(item, "PRODUCTCLASS"));
            d.setPreSettlePrice(dec(item, "PRESETTLEMENTPRICE"));
            d.setOpenPrice(dec(item, "OPENPRICE"));
            d.setHighPrice(dec(item, "HIGHESTPRICE"));
            d.setLowPrice(dec(item, "LOWESTPRICE"));
            d.setClosePrice(dec(item, "CLOSEPRICE"));
            d.setSettlePrice(dec(item, "SETTLEMENTPRICE"));
            d.setChangeValue(dec(item, "ZD1_CHG"));
            d.setChangeValue2(dec(item, "ZD2_CHG"));
            d.setVolume(dec(item, "VOLUME"));
            d.setOpenInterest(dec(item, "OPENINTEREST"));
            d.setOpenInterestChange(dec(item, "OPENINTERESTCHG"));
            d.setTurnover(dec(item, "TURNOVER"));
            d.setTasVolume(dec(item, "TASVOLUME"));
            out.add(d);
        }
    }

    /** 品种汇总：avg_price / year_volume / year_turnover 恰好用上表里的专用列 */
    private void parseProducts(JSONArray array, String tradeDate, String updateTime,
                               List<ShfeDailyData> out) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            String productName = str(item, "PRODUCTNAME");
            ShfeDailyData d = base(tradeDate, updateTime, "PRODUCT", productName, item);
            d.setProductName(productName);
            d.setProductId(str(item, "PRODUCTID"));
            d.setProductGroup(str(item, "PRODUCTGROUPID"));
            d.setHighPrice(dec(item, "HIGHESTPRICE"));
            d.setLowPrice(dec(item, "LOWESTPRICE"));
            d.setAvgPrice(dec(item, "AVGPRICE"));
            d.setVolume(dec(item, "VOLUME"));
            d.setTurnover(dec(item, "TURNOVER"));
            d.setYearVolume(dec(item, "YEARVOLUME"));
            d.setYearTurnover(dec(item, "YEARTURNOVER"));
            out.add(d);
        }
    }

    /** 金属指数：UPDOWN1 无对应表列，随 raw_json 原样保留 */
    private void parseMetalIndex(JSONArray array, String tradeDate, String updateTime,
                                 String indexSymbol, List<ShfeDailyData> out) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            String day = str(item, "TRADINGDAY");
            ShfeDailyData d = base(day == null ? tradeDate : toIsoDate(day),
                    updateTime, "INDEX", indexSymbol, item);
            d.setOpenPrice(dec(item, "OPENPRICE"));
            d.setHighPrice(dec(item, "HIGHESTPRICE"));
            d.setLowPrice(dec(item, "LOWESTPRICE"));
            d.setClosePrice(dec(item, "CLOSEPRICE"));
            d.setLastPrice(dec(item, "LASTPRICE"));
            d.setPreClosePrice(dec(item, "PRECLOSEPRICE"));
            d.setSettlePrice(dec(item, "SETTLEMENTPRICE"));
            d.setAvgPrice(dec(item, "AVGPRICE"));
            d.setChangeValue(dec(item, "UPDOWN"));
            d.setChangeValue2(dec(item, "UPDOWN2"));
            out.add(d);
        }
    }

    private static ShfeDailyData base(String tradeDate, String updateTime, String rowType,
                                      String symbol, JSONObject item) {
        ShfeDailyData d = new ShfeDailyData();
        d.setTaskType(TASK_QUOTE);       // 表 NOT NULL，无默认值
        d.setTradeDate(tradeDate);
        d.setRowType(rowType);
        d.setSymbol(symbol);             // 表 NOT NULL，无默认值，唯一键之一
        d.setSourceUpdateTime(updateTime);
        d.setRawJson(item.toJSONString()); // 原始数据全字段兜底
        return d;
    }

    /** 原始值可能是 "" / 数字 / 科学计数法字符串，统一转 BigDecimal，空值返回 null */
    private static BigDecimal dec(JSONObject item, String key) {
        Object v = item.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(JSONObject item, String key) {
        Object v = item == null ? null : item.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private String extractDate(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = DATE_PATTERN.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** yyyyMMdd → yyyy-MM-dd（表的 trade_date 是 DATE 类型） */
    private static String toIsoDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() != 8) {
            return yyyymmdd;
        }
        try {
            return LocalDate.parse(yyyymmdd, DateTimeFormatter.BASIC_ISO_DATE).toString();
        } catch (Exception e) {
            return yyyymmdd;
        }
    }

    @Override
    public Site getSite() {
        return site;
    }

    /** 默认采集 URL（由 crawler.shfe.url-template + default-date 组装） */
    public static String defaultUrl() {
        return urlFor(CrawlerConfig.str(CFG + "default-date", "20251031"));
    }

    /** 组装当日行情 URL（如 kx20261110.dat），时区取 crawler.schedule.zone */
    public static String urlForToday() {
        ZoneId zone = ZoneId.of(CrawlerConfig.str("crawler.schedule.zone", "Asia/Shanghai"));
        return urlFor(LocalDate.now(zone).format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    /** 按交易日组装 URL */
    public static String urlFor(String tradeDate) {
        return String.format(CrawlerConfig.str(CFG + "url-template",
                "https://www.shfe.com.cn/data/tradedata/future/dailydata/kx%s.dat"), tradeDate);
    }

    /** 只下载每日 JSON 落盘，不入库（Downloader 负责保存文件，Pipeline 为空实现） */
    public static void downloadOnly(String url) {
        Spider.create(new ShfeProcessor())
                .setDownloader(ShfeDownloader.create())
                .setScheduler(new ShfeScheduler())
                .addPipeline((items, task) -> { })
                .addUrl(url)
                .thread(1)
                .run();
    }

    /** 单次抓取：显式装配四大组件 */
    public static void runOnce(String url) {
        Spider.create(new ShfeProcessor())                // PageProcessor
                .setDownloader(ShfeDownloader.create())  // Downloader
                .setScheduler(new ShfeScheduler())       // Scheduler
                .addPipeline(new ShfePipeline())         // Pipeline
                .addUrl(url)
                .thread(CrawlerConfig.intVal(CFG + "spider.thread", 1))
                .run();
    }

    /** 直接运行：java com.zjysky.crawler.processor.ShfeProcessor [url] */
    public static void main(String[] args) {
        runOnce(args.length > 0 ? args[0] : defaultUrl());
    }
}
