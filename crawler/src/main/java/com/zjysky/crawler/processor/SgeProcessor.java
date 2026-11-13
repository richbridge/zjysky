package com.zjysky.crawler.processor;

import com.zjysky.crawler.CrawlerConfig;
import com.zjysky.crawler.PriceSupport;
import com.zjysky.common.database.entity.MdPriceSge;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.processor.PageProcessor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SGE 上金所的 PageProcessor，两类页面按 URL 自动分派：
 *
 * <ul>
 *   <li><b>每日行情 Excel</b>（收盘后才有，正式口径 QUOTE）
 *       {@code downloadExcelForQuoteDailyNew?start_date=..&end_date=..}：
 *       Downloader 已把 xlsx 字节放进 Page.bytes，本类用 Apache POI 读第一个工作表；</li>
 *   <li><b>当日延时行情 HTML</b>（盘中就有，临时口径 REALTIME）
 *       {@code https://www.sge.com.cn/sjzx/yshqbg}：服务端渲染，jsoup 直接读表格。</li>
 * </ul>
 *
 * <p>两类解析完都 {@code page.putField("sgeList", list)}，复用同一个
 * {@code SgePipeline} 落库；REALTIME 行在收盘 Excel 落库后被清理，
 * 实现「收盘覆盖实时」。</p>
 *
 * <p>容易踩的坑：</p>
 * <ol>
 *   <li>Excel 成交金额可能是 {@code 1.30714E7}，由 PriceSupport.decimal 统一转换；
 *       涨跌幅 {@code 1.55%} 由 PriceSupport.percent 转成 1.55；{@code -} 表示空值；</li>
 *   <li>延时行情表里的 {@code 0.0} 是源站「今天没成交」的占位值，照实写 raw_json，不做猜改；</li>
 *   <li>md_price_sge 没有 last_price 列，「最新价」映射到 close_price，
 *       收盘后被 Excel 真收盘价覆盖。</li>
 * </ol>
 */
public class SgeProcessor implements PageProcessor {

    private static final String CFG = "crawler.sge.";
    private static final String RT_CFG = "crawler.sge.realtime.";
    private static final Pattern END_DATE = Pattern.compile("[?&]end_date=([^&]+)");
    private static final Pattern DATE_CN = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日");

    /** 延时行情页表头 → 需要的列。只取这 5 列，其它内容进 raw_json。 */
    private static final Map<String, String> HEADER_TO_FIELD = Map.of(
            "合约", "contract",
            "最新价", "closePrice",
            "最高价", "highPrice",
            "最低价", "lowPrice",
            "今开盘", "openPrice");

    private final Site site = buildSite();

    private static Site buildSite() {
        return Site.me()
                .setRetryTimes(CrawlerConfig.intVal(CFG + "site.retry-times", 3))
                .setSleepTime(CrawlerConfig.intVal(CFG + "site.sleep-time", 1000))
                .setTimeOut(CrawlerConfig.intVal(CFG + "site.time-out", 20000))
                .setCharset(CrawlerConfig.str(CFG + "site.charset", "UTF-8"))
                .setUserAgent(CrawlerConfig.str(CFG + "site.user-agent", "Mozilla/5.0"))
                .addHeader("Referer", CrawlerConfig.str(CFG + "site.referer", "https://www.sge.com.cn/"));
    }

    @Override
    public void process(Page page) {
        String url = page.getUrl() == null ? "" : page.getUrl().get();
        if (url.contains("downloadExcelForQuoteDailyNew")) {
            parseExcel(page, url);
        } else if (url.contains("/sjzx/yshqbg") || url.contains("delaymarket")) {
            parseRealtimeHtml(page, url);
        } else {
            System.err.println(">>> [SGE] 未知采集目标（只认每日行情 Excel 与延时行情页）: " + url);
            page.setSkip(true);
        }
    }

    // ================================================================
    // ① 每日行情 Excel
    // ================================================================

    private void parseExcel(Page page, String url) {
        byte[] bytes = page.getBytes();
        if (bytes == null || bytes.length == 0) {
            // 兜底：若 Downloader 没开二进制模式，rawText 也许可用；但正常配置不会走到这里
            String raw = page.getRawText();
            bytes = raw == null ? null : raw.getBytes(StandardCharsets.UTF_8);
        }
        if (!looksLikeExcel(bytes)) {
            System.err.println(">>> [SGE] 返回内容不是 Excel，前 32 字节: " + hexPrefix(bytes, 32));
            page.setSkip(true);
            return;
        }

        String fallbackDate = dateFromUrl(url);
        String taskType = CrawlerConfig.str(CFG + "task-type", "QUOTE");
        List<MdPriceSge> list = new ArrayList<>(40);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                page.setSkip(true);
                return;
            }
            DataFormatter formatter = new DataFormatter();
            int colCount = headerRow.getLastCellNum();

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                // 整行原始值先留档，未映射到表列的字段也不会丢
                Map<String, String> raw = new LinkedHashMap<>();
                for (int j = 0; j < colCount; j++) {
                    Cell headerCell = headerRow.getCell(j);
                    String header = headerCell == null ? null : formatter.formatCellValue(headerCell).trim();
                    if (header == null || header.isBlank()) {
                        header = "列" + (j + 1);
                    }
                    raw.put(header, formatter.formatCellValue(row.getCell(j)).trim());
                }

                String contract = pick(raw, "合约");
                if (contract == null) {
                    continue;
                }
                String tradeDate = PriceSupport.date(pick(raw, "日期"));
                if (tradeDate == null) {
                    tradeDate = fallbackDate;
                }
                if (tradeDate == null) {
                    System.err.println(">>> [SGE] 跳过无日期的行（合约=" + contract + "），请检查 Excel 或 end_date 参数");
                    continue;
                }

                MdPriceSge d = new MdPriceSge();
                d.setTaskType(taskType);
                d.setTradeDate(PriceSupport.localDate(tradeDate));
                d.setContract(contract);
                d.setSeqNo(PriceSupport.intValue(pick(raw, "序号")));
                d.setOpenPrice(PriceSupport.decimal(pick(raw, "开盘价")));
                d.setHighPrice(PriceSupport.decimal(pick(raw, "最高价")));
                d.setLowPrice(PriceSupport.decimal(pick(raw, "最低价")));
                d.setClosePrice(PriceSupport.decimal(pick(raw, "收盘价")));
                // 当前 Excel 没有「结算价」列；字段保留给其它口径，读到就写，读不到为 null
                d.setSettlePrice(PriceSupport.decimal(pick(raw, "结算价")));
                d.setAvgPrice(PriceSupport.decimal(pick(raw, "加权平均价")));
                d.setChangeValue(PriceSupport.decimal(pick(raw, "涨跌（元）", "涨跌")));
                d.setChangePct(PriceSupport.percent(pick(raw, "涨跌幅")));
                d.setVolume(PriceSupport.decimal(pick(raw, "成交量（kg）", "成交量")));
                d.setOpenInterest(PriceSupport.decimal(pick(raw, "市场持仓（手）", "持仓量")));
                d.setDeliveryDirection(pick(raw, "交收方向"));
                d.setDeliveryVolume(PriceSupport.decimal(pick(raw, "交收量（手）")));
                d.setTurnover(PriceSupport.decimal(pick(raw, "成交金额（元）")));
                d.setRawJson(PriceSupport.json(raw));
                list.add(d);
            }
        } catch (Exception e) {
            System.err.println(">>> [SGE] Excel 解析失败: " + e.getMessage());
            page.setSkip(true);
            return;
        }

        if (list.isEmpty()) {
            page.setSkip(true);
            return;
        }
        System.out.println(">>> [SGE] Excel 解析完成: " + list.size() + " 行，日期="
                + list.get(0).getTradeDate() + "，task_type=" + taskType);
        page.putField("sgeList", list);
    }

    // ================================================================
    // ② 当日延时行情 HTML
    // ================================================================

    private void parseRealtimeHtml(Page page, String url) {
        String html = page.getRawText();
        if (html == null || html.isBlank()) {
            System.err.println(">>> [SGE 实时] 页面内容为空: " + url);
            page.setSkip(true);
            return;
        }

        Document doc = Jsoup.parse(html);
        Element table = findQuoteTable(doc);
        if (table == null) {
            System.err.println(">>> [SGE 实时] 没找到含「合约 / 最新价」表头的行情表，页面结构可能已改版");
            page.setSkip(true);
            return;
        }

        // 先把表头读成「列名 → 第几列」，这样即使列顺序调整也不会错位
        Map<String, Integer> headerIndex = new LinkedHashMap<>();
        Element headerRow = table.selectFirst("tr");
        if (headerRow == null) {
            page.setSkip(true);
            return;
        }
        int column = 0;
        for (Element cell : headerRow.select("th, td")) {
            String name = cell.text().trim();
            if (!name.isEmpty()) {
                headerIndex.put(name, column);
            }
            column++;
        }
        if (!headerIndex.containsKey("合约") || !headerIndex.containsKey("最新价")) {
            System.err.println(">>> [SGE 实时] 表头不符合预期: " + headerIndex.keySet());
            page.setSkip(true);
            return;
        }

        String tradeDate = tradeDate(doc);
        String taskType = CrawlerConfig.str(RT_CFG + "task-type", "REALTIME").trim();
        List<MdPriceSge> list = new ArrayList<>(32);
        int seq = 0;

        Elements rows = table.select("tr");
        for (int i = 1; i < rows.size(); i++) {
            Elements cells = rows.get(i).select("td");
            if (cells.isEmpty()) {
                continue;
            }
            String contract = cell(cells, headerIndex, "合约");
            if (contract == null || contract.isBlank() || "合约".equals(contract)) {
                continue;
            }

            // 整行原始值留档：未映射到表列的表头也不会丢
            Map<String, String> raw = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : headerIndex.entrySet()) {
                String value = cell(cells, e.getValue());
                if (value != null) {
                    raw.put(e.getKey(), value);
                }
            }

            MdPriceSge d = new MdPriceSge();
            d.setTaskType(taskType);
            d.setTradeDate(PriceSupport.localDate(tradeDate));
            d.setContract(contract);
            d.setSeqNo(++seq);
            // 表无 last_price 列：源站「最新价」→ close_price，收盘后被真收盘价覆盖
            d.setClosePrice(PriceSupport.decimal(cell(cells, headerIndex, "最新价")));
            d.setHighPrice(PriceSupport.decimal(cell(cells, headerIndex, "最高价")));
            d.setLowPrice(PriceSupport.decimal(cell(cells, headerIndex, "最低价")));
            d.setOpenPrice(PriceSupport.decimal(cell(cells, headerIndex, "今开盘")));
            d.setRawJson(PriceSupport.json(raw));
            list.add(d);
        }

        if (list.isEmpty()) {
            System.err.println(">>> [SGE 实时] 解析到 0 行，跳过落库");
            page.setSkip(true);
            return;
        }
        System.out.println(">>> [SGE 实时] 延时行情解析完成: " + list.size()
                + " 个合约，日期=" + tradeDate + "，task_type=" + taskType);
        page.putField("sgeList", list);
    }

    // ================================================================
    // 共用小工具
    // ================================================================

    /** 在原始 Map 里取第一个非空值（兼容表头文案微调）。 */
    private static String pick(Map<String, String> row, String... keys) {
        for (String key : keys) {
            String v = row.get(key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** Excel URL 里的 end_date 作为日期兜底。 */
    private static String dateFromUrl(String url) {
        Matcher m = END_DATE.matcher(url);
        return m.find() ? PriceSupport.date(m.group(1)) : null;
    }

    /** xlsx 文件头是 PK...；老式 xls 是 D0 CF 11 E0。 */
    private static boolean looksLikeExcel(byte[] b) {
        if (b == null || b.length < 4) {
            return false;
        }
        if (b[0] == 'P' && b[1] == 'K') {
            return true;
        }
        return (b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF
                && (b[2] & 0xFF) == 0x11 && (b[3] & 0xFF) == 0xE0;
    }

    private static String hexPrefix(byte[] b, int n) {
        if (b == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) {
            sb.append(String.format("%02X ", b[i]));
        }
        return sb.toString().trim();
    }

    /** 优先取 div.memberName 里的表；页面改版时退回「表头含合约+最新价」的第一张表。 */
    private static Element findQuoteTable(Document doc) {
        Element exact = doc.selectFirst("div.memberName table");
        if (exact != null && exact.selectFirst("tr") != null
                && exact.text().contains("最新价")) {
            return exact;
        }
        for (Element table : doc.select("table")) {
            Element first = table.selectFirst("tr");
            if (first == null) {
                continue;
            }
            String head = first.text();
            if (head.contains("合约") && head.contains("最新价")) {
                return table;
            }
        }
        return null;
    }

    /** 按表头名取单元格文本。 */
    private static String cell(Elements cells, Map<String, Integer> headerIndex, String name) {
        Integer idx = headerIndex.get(name);
        return idx == null ? null : cell(cells, idx);
    }

    private static String cell(Elements cells, int index) {
        if (index < 0 || index >= cells.size()) {
            return null;
        }
        String text = cells.get(index).text().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 延时行情交易日：优先从页面大标题「上海黄金交易所2026年11月11日延时行情」里取；
     * 取不到再读配置 crawler.sge.realtime.default-date，最后兜底用当天。
     */
    private static String tradeDate(Document doc) {
        for (Element h : doc.select("h1, h2")) {
            Matcher m = DATE_CN.matcher(h.text());
            if (m.find()) {
                return String.format("%04d-%02d-%02d",
                        Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)));
            }
        }
        String configured = PriceSupport.date(CrawlerConfig.str(RT_CFG + "default-date", ""));
        if (configured != null) {
            return configured;
        }
        return LocalDate.now(ZoneId.of(
                CrawlerConfig.str("crawler.market-close.zone", "Asia/Shanghai"))).toString();
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
