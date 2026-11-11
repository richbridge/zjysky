package com.zjysky.crawler.processor;

import com.zjysky.crawler.config.CrawlerConfig;
import com.zjysky.crawler.model.MdPriceSge;
import com.zjysky.crawler.support.PriceSupport;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 四大组件之一：PageProcessor（SGE 上金所）。
 *
 * <p><b>只做解析</b>：Downloader 已经把 xlsx 的字节放进 {@code Page.bytes}，
 * 本类用 Apache POI 读第一个工作表，把每行转成 {@link MdPriceSge}，
 * 再用 {@code page.putField("sgeList", list)} 交给 Pipeline。</p>
 *
 * <p>实测 Excel 表头（2025-11-10）：</p>
 * <pre>
 * 序号 | 日期 | 合约 | 开盘价 | 最高价 | 最低价 | 收盘价 | 涨跌（元） | 涨跌幅 |
 * 加权平均价 | 成交量（kg） | 成交金额（元） | 市场持仓（手） | 交收方向 | 交收量（手）
 * </pre>
 *
 * <p>注意几点容易踩的坑：</p>
 * <ul>
 *   <li>接口必须用 GET 下载，HEAD 会返回 405；这些由 Downloader / Spider 处理。</li>
 *   <li>成交金额在 Excel 里可能是 {@code 1.30714E7}，由 {@link PriceSupport#decimal} 统一转换。</li>
 *   <li>涨跌幅是 {@code 1.55%} 字符串，由 {@link PriceSupport#percent} 转成 1.55 写入 DECIMAL。</li>
 *   <li>{@code -} 表示空值，不能当数字写，否则整批 INSERT 会失败。</li>
 * </ul>
 */
public class SgeProcessor implements PageProcessor {

    private static final String CFG = "crawler.sge.";
    private static final Pattern END_DATE = Pattern.compile("[?&]end_date=([^&]+)");

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
        if (!url.contains("downloadExcelForQuoteDailyNew")) {
            System.err.println(">>> [SGE] 未知采集目标（本处理器只认每日行情 Excel）: " + url);
            page.setSkip(true);
            return;
        }

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
                d.setTradeDate(tradeDate);
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

    /** URL 里的 end_date 作为日期兜底。 */
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

    @Override
    public Site getSite() {
        return site;
    }
}
