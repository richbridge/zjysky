package com.zjysky.crawler.spider;

import com.zjysky.crawler.CrawlerConfig;
import com.zjysky.crawler.downloader.ShfeDownloader;
import com.zjysky.crawler.pipeline.ShfePipeline;
import com.zjysky.crawler.processor.ShfeProcessor;
import us.codecraft.webmagic.scheduler.QueueScheduler;
import us.codecraft.webmagic.Spider;
import us.codecraft.webmagic.pipeline.Pipeline;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 爬虫的「总指挥」：Spider。单独放在 spider 文件夹。
 *
 * <p>WebMagic 把一次采集拆成四件事，每件事由一个组件负责。Spider 自己不下载、不解析、
 * 也不写数据库，它只做两件事：<b>把四个组件组装起来</b>，然后<b>按顺序调度它们</b>。</p>
 *
 * <pre>
 *   addUrl("https://.../kx20251031.dat")              历史日行情
 *   addUrl("https://.../delaymarket_all.dat")         当日延时行情
 *        │
 *        ▼
 *   ① Scheduler     保存待抓地址、去掉重复地址
 *        │
 *        ▼
 *   ② Downloader    下载 .dat 文本 JSON（真相已落盘到 data/shfe），放进 Page.rawText
 *        │
 *        ▼
 *   ③ PageProcessor 自动识别两类报文，解析成 List&lt;MdPriceShfe&gt;
 *        │
 *        ▼
 *   ④ Pipeline      取出 shfeList，批量 upsert 写入 md_price_shfe
 * </pre>
 *
 * <p>两类行情靠 {@code task_type} 区分：历史 = QUOTE（收盘定稿），
 * 当日延时 = REALTIME（盘中临时价）。收盘历史落库成功后，Pipeline 会按需求
 * 删除同日 REALTIME 行，实现「实时数据收盘后被收盘数据覆盖」。</p>
 *
 * <p>本类只负责「怎么采」；「这次该采实时还是收盘、要不要跳过」由
 * {@code com.zjysky.crawler.SourceSchedule} 决定。定时判断不写在这里。</p>
 */
public final class ShfeSpider {

    /** 配置前缀，对应 application.yml 里的 crawler.shfe.* */
    private static final String CFG = "crawler.shfe.";
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    private ShfeSpider() {
    }

    /**
     * 组装四大组件，返回一个「还没开始跑」的 Spider。
     *
     * @param pipeline 第 ④ 步怎么处理解析结果：传 {@link ShfePipeline} 就落库；
     *                 传一个空实现就只下载、不入库
     */
    public static Spider create(Pipeline pipeline) {
        return Spider.create(new ShfeProcessor())             // ③ 解析 JSON → List<MdPriceShfe>
                .setDownloader(ShfeDownloader.create())  // ② 下载 + 把原始 .dat 存成文件
                .setScheduler(new QueueScheduler())         // ① URL 队列 + 去重
                .addPipeline(pipeline)                       // ④ 处理结果（落库 / 丢弃）
                .thread(CrawlerConfig.intVal(CFG + "spider.thread", 1));
    }

    /** 通用入口：采集一个 URL 并落库，URL 决定是历史还是当日延时。 */
    public static void runOnce(String url) {
        SpiderRegistry.run(create(new ShfePipeline()).addUrl(url));
    }

    /** 只下载原始 .dat 并落盘，不解析、不写数据库。 */
    public static void downloadOnly(String url) {
        SpiderRegistry.run(create((resultItems, task) -> { }).addUrl(url));
    }

    // ============================================================
    // 两类采集的便捷入口
    // ============================================================

    /** 当日延时行情：delaymarket_all.dat → md_price_shfe（task_type=REALTIME）。 */
    public static void runTodayDelay() {
        runOnce(urlForTodayDelay());
    }

    /** 当日实时（需求 4 的语义化入口，等价于 runTodayDelay）。 */
    public static void runRealtime() {
        System.out.println(">>> [SHFE] 当日实时采集：盘中延时行情 → task_type="
                + CrawlerConfig.str(CFG + "today-task-type", "REALTIME"));
        runTodayDelay();
    }

    /** 只下载当日延时行情原始文件。 */
    public static void downloadTodayDelay() {
        downloadOnly(urlForTodayDelay());
    }

    /** 只下载当日实时原始文件（与 runRealtime 对称）。 */
    public static void downloadRealtime() {
        downloadTodayDelay();
    }

    /** 历史日行情：kx{yyyyMMdd}.dat → md_price_shfe（task_type=QUOTE）。 */
    public static void runHistory(String date) {
        runOnce(urlForHistory(date));
    }

    /** 只下载指定交易日的历史日行情原始文件。 */
    public static void downloadHistory(String date) {
        downloadOnly(urlForHistory(date));
    }

    // ============================================================
    // URL 小工具：告诉 Spider 要抓哪个地址
    // ============================================================

    /** 默认历史交易日，取自 application.yml。 */
    public static String defaultUrl() {
        return urlForHistory(CrawlerConfig.str(CFG + "default-date", "20251031"));
    }

    /** 今日的历史日行情 URL（保留旧方法名，供已有调用方使用）。 */
    public static String urlForToday() {
        return urlForHistory(LocalDate.now(zone()).format(BASIC));
    }

    /** 旧版 URL 工具：按 yyyyMMdd 生成历史日行情地址。 */
    public static String urlFor(String date) {
        return urlForHistory(date);
    }

    /** 当日实时 URL。?params= 只是防缓存时间戳，服务端忽略具体值。 */
    public static String urlForTodayDelay() {
        return CrawlerConfig.str(CFG + "today-url",
                "https://www.shfe.com.cn/data/tradedata/future/delaymarket/delaymarket_all.dat"
                        + "?params=1763002691819");
    }

    /** 当日语义化 URL（与 runRealtime 对称）。 */
    public static String urlForRealtime() {
        return urlForTodayDelay();
    }

    /** 历史日行情 URL：接受 yyyyMMdd 或 yyyy-MM-dd。 */
    public static String urlForHistory(String date) {
        String template = CrawlerConfig.str(CFG + "history-url-template",
                CrawlerConfig.str(CFG + "url-template",
                        "https://www.shfe.com.cn/data/tradedata/future/dailydata/kx%s.dat"));
        return String.format(template, normalizeYmd(date));
    }

    /** yyyy-MM-dd / yyyyMMdd → yyyyMMdd。 */
    public static String normalizeYmd(String date) {
        if (date == null || date.isBlank()) {
            return CrawlerConfig.str(CFG + "history-default-date", "20251031");
        }
        return date.trim().replace("-", "");
    }

    private static ZoneId zone() {
        return ZoneId.of(CrawlerConfig.str("crawler.schedule.zone", "Asia/Shanghai"));
    }

    /** 单独跑一次：java com.zjysky.crawler.spider.ShfeSpider [today|history:20251031|url] */
    public static void main(String[] args) {
        if (args.length == 0) {
            runTodayDelay();
            return;
        }
        String arg = args[0];
        if ("today".equalsIgnoreCase(arg) || "realtime".equalsIgnoreCase(arg)) {
            runRealtime();
        } else if ("download".equalsIgnoreCase(arg)) {
            downloadTodayDelay();
        } else if ("download-realtime".equalsIgnoreCase(arg)) {
            downloadRealtime();
        } else if ("history".equalsIgnoreCase(arg)) {
            runHistory(args.length > 1 ? args[1] : null);
        } else if (arg.startsWith("http")) {
            runOnce(arg);
        } else {
            runHistory(arg);
        }
    }
}
