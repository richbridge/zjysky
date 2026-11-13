package com.zjysky.crawler.spider;

import com.zjysky.crawler.CrawlerConfig;
import com.zjysky.crawler.downloader.SgeDownloader;
import com.zjysky.crawler.guard.CloseTimeGuard;
import com.zjysky.crawler.pipeline.SgePipeline;
import com.zjysky.crawler.processor.SgeProcessor;
import us.codecraft.webmagic.scheduler.QueueScheduler;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Spider;
import us.codecraft.webmagic.pipeline.Pipeline;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * SGE 上金所爬虫的「总指挥」。
 *
 * <p><b>两条链路，四个组件各一份</b>：</p>
 * <pre>
 * ① 收盘历史（QUOTE，价格定稿后）
 *    SgeSpider.runDate("2025-11-10")
 *      ▼ RawDownloader.create("sge")       GET 下载 xlsx（binary=true）→ data/sge
 *      ▼ SgeProcessor                     Apache POI 读 Excel → List&lt;MdPriceSge&gt;
 *      ▼ SgePipeline                      字段自检 + upsert + 清理同日 REALTIME
 *
 * ② 当日实时（REALTIME，盘中）
 *    SgeSpider.runRealtime()
 *      ▼ RawDownloader.create("sge.realtime")  GET 下载延时行情 HTML → data/sge-realtime
 *      ▼ SgeRealtimeProcessor                 jsoup 解析表格 → List&lt;MdPriceSge&gt;
 *      ▼ SgePipeline                          复用同一张表、同一套字段自检
 * </pre>
 *
 * <p>历史 Excel 地址（HEAD 会 405，必须用 GET）：</p>
 * <pre>
 * https://www.sge.com.cn/portal/marketAutomation/downloadExcelForQuoteDailyNew
 *   ?start_date=2025-11-10&amp;end_date=2025-11-10
 * </pre>
 *
 * <p>当日实时地址：<code>https://www.sge.com.cn/sjzx/yshqbg</code>（服务端渲染 HTML）。
 * 收盘历史落库成功后，Pipeline 会删除同日 REALTIME 行，实现「收盘覆盖实时」。</p>
 */
public final class SgeSpider {

    private static final String CFG = "crawler.sge.";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private SgeSpider() {
    }

    private static final String RT_CFG = "crawler.sge.realtime.";

    /** 组装「收盘历史 Excel」链路。 */
    public static Spider create(Pipeline pipeline) {
        return Spider.create(new SgeProcessor())
                .setDownloader(SgeDownloader.create())
                .setScheduler(new QueueScheduler())
                .addPipeline(pipeline)
                .thread(CrawlerConfig.intVal(CFG + "spider.thread", 1));
    }

    /** 组装「当日实时 HTML」链路：专用 Downloader + 专用 Processor，Pipeline 复用。 */
    public static Spider createRealtime(Pipeline pipeline) {
        return Spider.create(new SgeProcessor())
                .setDownloader(SgeDownloader.forRealtime())
                .setScheduler(new QueueScheduler())
                .addPipeline(pipeline)
                .thread(CrawlerConfig.intVal(RT_CFG + "spider.thread",
                        CrawlerConfig.intVal(CFG + "spider.thread", 1)));
    }

    /** 采集指定 Excel URL 并落库（收盘历史口径）。 */
    public static void runOnce(String url) {
        Request request = new Request(url);
        request.setBinaryContent(true);
        SpiderRegistry.run(create(new SgePipeline()).addRequest(request));
    }

    /** 只下载 Excel 原始文件，不解析、不入库。 */
    public static void downloadOnly(String url) {
        Request request = new Request(url);
        request.setBinaryContent(true);
        SpiderRegistry.run(create((resultItems, task) -> { }).addRequest(request));
    }

    /** 当日实时：延时行情 HTML → md_price_sge（task_type=REALTIME）。 */
    public static void runRealtime() {
        System.out.println(">>> [SGE] 当日实时采集：延时行情 HTML → task_type="
                + CrawlerConfig.str(RT_CFG + "task-type", "REALTIME"));
        SpiderRegistry.run(createRealtime(new SgePipeline()).addRequest(
                new Request(urlForRealtime())));
    }

    /** 只下载当日实时 HTML，不解析、不入库。 */
    public static void downloadRealtime() {
        SpiderRegistry.run(createRealtime((resultItems, task) -> { }).addRequest(
                new Request(urlForRealtime())));
    }

    /** 当日实时页地址，来自 crawler.sge.realtime.url。 */
    public static String urlForRealtime() {
        return CrawlerConfig.str(RT_CFG + "url", "https://www.sge.com.cn/sjzx/yshqbg");
    }

    /** 采集指定日期（yyyy-MM-dd 或 yyyyMMdd）。 */
    public static void runDate(String date) {
        runOnce(urlFor(date));
    }

    /** 只下载指定日期的 Excel。 */
    public static void downloadDate(String date) {
        downloadOnly(urlFor(date));
    }

    /** 默认日期：application.yml 的 crawler.sge.default-date。 */
    public static String defaultUrl() {
        return urlFor(CrawlerConfig.str(CFG + "default-date", "2025-11-10"));
    }

    /**
     * 拼 URL：start_date 与 end_date 相同，即取单日行情。
     *
     * <p>传入 yyyyMMdd 也会自动转成 yyyy-MM-dd，因为实测接口不认紧凑格式。</p>
     */
    public static String urlFor(String date) {
        String template = CrawlerConfig.str(CFG + "url-template",
                "https://www.sge.com.cn/portal/marketAutomation/downloadExcelForQuoteDailyNew"
                        + "?start_date=%s&end_date=%s");
        String day = normalizeDate(date);
        return String.format(template, day, day);
    }

    /**
     * 上一个工作日的行情地址。
     *
     * <p>工作日来自 common 模块的 {@code MicaHolidays}（mica 官方节假日库，
     * 含调休上班日），由 {@link CloseTimeGuard#previousWorkday(LocalDate)} 提供；
     * 因此春节 / 国庆等长假后也能正确回退到最近一个交易日。</p>
     */
    public static String urlForPreviousWorkday() {
        LocalDate day = CloseTimeGuard.previousWorkday(CloseTimeGuard.now().toLocalDate());
        return urlFor(day.format(ISO));
    }

    private static String normalizeDate(String date) {
        String d = date == null || date.isBlank()
                ? CrawlerConfig.str(CFG + "default-date", "2025-11-10")
                : date.trim();
        if (d.matches("\\d{8}")) {
            return d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8);
        }
        return d;
    }

    /** 单独跑一次：java com.zjysky.crawler.spider.SgeSpider [2025-11-10|prev|url] */
    public static void main(String[] args) {
        if (args.length == 0) {
            runOnce(defaultUrl());
            return;
        }
        String arg = args[0];
        if ("prev".equalsIgnoreCase(arg)) {
            runOnce(urlForPreviousWorkday());
        } else if ("realtime".equalsIgnoreCase(arg)) {
            runRealtime();
        } else if ("download-realtime".equalsIgnoreCase(arg)) {
            downloadRealtime();
        } else if ("download".equalsIgnoreCase(arg)) {
            downloadDate(args.length > 1 ? args[1] : null);
        } else if (arg.startsWith("http")) {
            runOnce(arg);
        } else {
            runDate(arg);
        }
    }
}
