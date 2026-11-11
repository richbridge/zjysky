package com.zjysky.crawler.processor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zjysky.crawler.config.CrawlerConfig;
import com.zjysky.crawler.model.MdPriceShfe;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.processor.PageProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 四大组件之一：PageProcessor（页面解析器）。
 *
 * <p><b>本类只做一件事</b>：把 Downloader 放进 {@code Page.rawText} 的 SHFE 报文，
 * 解析成 {@code List<MdPriceShfe>}，再用 {@code page.putField("shfeList", list)}
 * 交给 Pipeline。它不下载、不启动爬虫、也不写数据库。</p>
 *
 * <p>支持两类 URL（由 {@link #process(Page)} 自动识别）：</p>
 * <ul>
 *   <li><b>历史日行情</b> {@code .../dailydata/kx20251031.dat}<br>
 *       顶层含 o_curinstrument（合约）/ o_curproduct（品种汇总）/ o_curmetalindex（金属指数）；
 *       {@code task_type = QUOTE}；</li>
 *   <li><b>当日延时行情</b> {@code .../delaymarket/delaymarket_all.dat}<br>
 *       顶层只有 {@code delaymarket}，是二维数组（26 组 / 约 302 条），字段全小写；
 *       {@code task_type = REALTIME}（当日实时），trade_date 取每行 updatetime 的日期部分。</li>
 * </ul>
 *
 * <p>两类行情用 task_type 区分，落库时不会互相覆盖（唯一键是
 * {@code (task_type, trade_date, symbol)}）。当日实时行在收盘历史落库后会被清理，
 * 所以库里最终只保留价格已定稿的 QUOTE 数据。</p>
 */
public class ShfeProcessor implements PageProcessor {

    private static final String CFG = "crawler.shfe.";
    private static final Pattern DATE_PATTERN = Pattern.compile("kx(\\d{8})");

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
        if (root == null) {
            page.setSkip(true);
            return;
        }

        String url = page.getUrl() == null ? "" : page.getUrl().get();
        boolean delay = url.contains("delaymarket_all.dat") || root.containsKey("delaymarket");
        List<MdPriceShfe> list = delay ? parseDelay(root) : parseHistory(root, url);

        if (list.isEmpty()) {
            page.setSkip(true);
            return;
        }
        page.putField("shfeList", list);
    }

    // ================================================================
    // 当日延时行情：delaymarket_all.dat
    // ================================================================

    /**
     * 延时行情是一组二维数组：每个子数组是一类品种，元素形如
     * {@code {"contractname":"ad2611","instrumentid":"ad","lastprice":"22545",...}}。
     */
    private List<MdPriceShfe> parseDelay(JSONObject root) {
        JSONArray groups = root.getJSONArray("delaymarket");
        if (groups == null || groups.isEmpty()) {
            System.err.println(">>> [SHFE] delaymarket 数组为空");
            return List.of();
        }
        // 当日延时接口给的是「盘中实时价」，用 REALTIME 与收盘 QUOTE 区分，
        // 收盘历史落库后由 RealtimeOverwrite 删除同日 REALTIME 行。
        String taskType = CrawlerConfig.str(CFG + "today-task-type", "REALTIME");
        List<MdPriceShfe> out = new ArrayList<>(320);
        for (int i = 0; i < groups.size(); i++) {
            JSONArray group = groups.getJSONArray(i);
            if (group == null) {
                continue;
            }
            for (int j = 0; j < group.size(); j++) {
                JSONObject item = group.getJSONObject(j);
                if (item == null) {
                    continue;
                }
                String symbol = str(item, "contractname");
                if (symbol == null) {
                    continue;
                }
                String instrumentId = str(item, "instrumentid");
                String updateTime = str(item, "updatetime");
                String tradeDate = toIsoDate(first10(updateTime));

                MdPriceShfe d = new MdPriceShfe();
                d.setTaskType(taskType);
                d.setTradeDate(tradeDate);
                d.setRowType("CONTRACT");
                d.setSymbol(symbol);
                d.setProductName(instrumentId);
                d.setProductId(instrumentId);
                d.setProductGroup(instrumentId);
                d.setContractMonth(monthOf(symbol, instrumentId));
                d.setOpenPrice(dec(item, "openprice"));
                d.setHighPrice(dec(item, "highprice"));
                d.setLowPrice(dec(item, "lowerprice"));
                // 延时行情报文里没有 closeprice / precloseprice，实时口径的"最新价"
                // 就是 lastprice。close_price 也写 lastprice，方便按统一列查询；
                // 收盘后 QUOTE 行会用真正的收盘价覆盖这一天的实时行。
                BigDecimal lastPrice = dec(item, "lastprice");
                d.setLastPrice(lastPrice);
                d.setClosePrice(lastPrice);
                d.setPreSettlePrice(dec(item, "presettlementprice"));
                d.setChangeValue(dec(item, "upperdown"));
                d.setVolume(dec(item, "volume"));
                d.setTasVolume(dec(item, "tasvolume"));
                d.setLastVolume(dec(item, "lastv"));
                d.setOpenInterest(dec(item, "openinterest"));
                d.setTurnover(dec(item, "turnover"));
                d.setBidPrice(dec(item, "bidprice"));
                d.setAskPrice(dec(item, "askprice"));
                d.setSourceUpdateTime(updateTime);
                d.setRawJson(item.toJSONString());
                out.add(d);
            }
        }
        System.out.println(">>> [SHFE] 当日延时行情解析完成: " + out.size()
                + " 条，task_type=" + taskType);
        return out;
    }

    /** 从合约名里去掉品种前缀，得到交割月：ad2611 → 2611；IMCI → null。 */
    private static String monthOf(String symbol, String instrumentId) {
        if (instrumentId == null || symbol.length() <= instrumentId.length()
                || !symbol.startsWith(instrumentId)) {
            return null;
        }
        String month = symbol.substring(instrumentId.length());
        return month.matches("\\d+") ? month : null;
    }

    /** "2026-11-11 11:30:00" → "2026-11-11"。 */
    private static String first10(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 10 ? s.substring(0, 10) : s;
    }

    // ================================================================
    // 历史日行情：kx{yyyyMMdd}.dat
    // ================================================================

    private List<MdPriceShfe> parseHistory(JSONObject root, String url) {
        String yyyymmdd = extractDate(url);
        if (yyyymmdd == null || yyyymmdd.isBlank()) {
            yyyymmdd = str(root, "report_date");
        }
        String tradeDate = toIsoDate(yyyymmdd);
        String updateTime = str(root, "update_date");
        String taskType = CrawlerConfig.str(CFG + "history-task-type", "QUOTE");
        String indexSymbol = CrawlerConfig.str(CFG + "index-symbol", "SHFE金属指数");

        List<MdPriceShfe> list = new ArrayList<>(360);
        parseInstruments(root.getJSONArray("o_curinstrument"), taskType, tradeDate, updateTime, list);
        parseProducts(root.getJSONArray("o_curproduct"), taskType, tradeDate, updateTime, list);
        parseMetalIndex(root.getJSONArray("o_curmetalindex"), taskType, tradeDate, updateTime,
                indexSymbol, list);
        System.out.println(">>> [SHFE] 历史日行情解析完成: " + tradeDate
                + " 共 " + list.size() + " 条，task_type=" + taskType);
        return list;
    }

    /** 合约行情：18 个原始字段 → 表列；"总计"行价格字段多为空串，统一转 null。 */
    private void parseInstruments(JSONArray array, String taskType, String tradeDate,
                                  String updateTime, List<MdPriceShfe> out) {
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

            MdPriceShfe d = base(taskType, tradeDate, updateTime, total ? "TOTAL" : "CONTRACT",
                    symbol, item);
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

    /** 品种汇总：avg_price / year_volume / year_turnover 恰好用上表里的专用列。 */
    private void parseProducts(JSONArray array, String taskType, String tradeDate,
                               String updateTime, List<MdPriceShfe> out) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            String productName = str(item, "PRODUCTNAME");
            MdPriceShfe d = base(taskType, tradeDate, updateTime, "PRODUCT", productName, item);
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

    /** 金属指数：UPDOWN1 无对应表列，随 raw_json 原样保留。 */
    private void parseMetalIndex(JSONArray array, String taskType, String tradeDate,
                                 String updateTime, String indexSymbol, List<MdPriceShfe> out) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            String day = str(item, "TRADINGDAY");
            MdPriceShfe d = base(taskType, day == null ? tradeDate : toIsoDate(day),
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

    private static MdPriceShfe base(String taskType, String tradeDate, String updateTime,
                                    String rowType, String symbol, JSONObject item) {
        MdPriceShfe d = new MdPriceShfe();
        d.setTaskType(taskType);       // 表 NOT NULL，无默认值
        d.setTradeDate(tradeDate);
        d.setRowType(rowType);
        d.setSymbol(symbol);           // 表 NOT NULL，无默认值，唯一键之一
        d.setSourceUpdateTime(updateTime);
        d.setRawJson(item.toJSONString()); // 原始数据全字段兜底
        return d;
    }

    /** 原始值可能是 "" / 数字 / 科学计数法字符串，统一转 BigDecimal，空值返回 null。 */
    private static BigDecimal dec(JSONObject item, String key) {
        Object v = item == null ? null : item.get(key);
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

    /** yyyyMMdd → yyyy-MM-dd（表的 trade_date 是 DATE 类型）。 */
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

    /**
     * WebMagic 会调用这个方法读取站点设置（超时、重试、UA、Referer 等）。
     * 这些参数由 Downloader 实际使用，但按 WebMagic 的约定写在 PageProcessor 里。
     */
    @Override
    public Site getSite() {
        return site;
    }
}
