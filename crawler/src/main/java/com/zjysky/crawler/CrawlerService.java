package com.zjysky.crawler;

import com.zjysky.crawler.spider.CjSpider;
import com.zjysky.crawler.spider.SgeSpider;
import com.zjysky.crawler.spider.ShfeSpider;
import com.zjysky.crawler.spider.SmmSpider;
import com.zjysky.crawler.spider.AbstractSpotSpider;
import com.zjysky.crawler.spider.SpiderRegistry;
import com.zjysky.crawler.scheduler.AbstractSourceScheduler;
import com.zjysky.crawler.guard.CloseTimeGuard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 统一启动入口。配置统一在 {@code crawler/src/main/resources/application.yml}。
 *
 * <p><b>模块结构</b>：WebMagic 四大组件 + Spider + 守卫，一共六个包；
 * 每个组件目录按数据源拆成四个类（Sge/Shfe/Smm/Cj 前缀）+ 一个共用基类；
 * 根目录只有本入口、全局配置 {@link CrawlerConfig}、共用工具 {@link PriceSupport}、
 * 数据库装配 {@link DbConfig} 和现货源差异表 {@link SpotSource}。
 * 数据库实体与 Mapper 全部来自 common 模块，crawler 不自己定义持久层。</p>
 * <pre>
 * crawler/src/main/java/com/zjysky/crawler/
 *   ├── downloader/  ① Downloader    AbstractSourceDownloader + Sge/Shfe/Smm/CjDownloader
 *   ├── processor/   ② PageProcessor AbstractSpotProcessor + Sge/Shfe/Smm/CjProcessor
 *   ├── scheduler/   ③ Scheduler     AbstractSourceScheduler + Sge/Shfe/Smm/CjScheduler
 *   │                                （URL 队列去重用 WebMagic 自带 QueueScheduler）
 *   ├── pipeline/    ④ Pipeline      AbstractPricePipeline + AbstractSpotPipeline
 *   │                                + Sge/Shfe/Smm/CjPipeline + CrawlMetaSupport
 *   ├── spider/      ★ Spider        AbstractSpotSpider + Cj/SmmSpider + Sge/ShfeSpider
 *   │                                + SpiderRegistry
 *   └── guard/       ★ 守卫          交易日/收盘判断（工作日取自 common.MicaHolidays）
 *                                    + 当天不重复采 + 收盘数据定稿判定
 * </pre>
 *
 * <p><b>触发时间</b>：SHFE / SGE 由 {@code crawler.schedule.times.<源>} 配置；
 * CJ / SMM 按「开盘后 30 分钟内一次、收盘后一次」自动推算
 * （{@code crawler.<源>.schedule.open-time / open-delay-minutes / close-delay-minutes}
 * + {@code crawler.market-close.close-times.<源>}），全部可配置。</p>
 *
 * <p><b>常用命令</b>（source 取 SHFE / SGE / CJ / SMM，大小写不敏感；
 * 以下命令均可加 nohup 长驻运行，参数为 HH:mm 时按该时刻定时）：</p>
 * <pre>
 * config / market-status                  生效配置 / 四源收盘状态
 * once SHFE [realtime|history 20251031|scheduled|&lt;URL&gt;]
 * once SGE [realtime|2025-11-10|prev|scheduled]
 * once CJ|SMM [all [起] [止] | one 合约 [起] [止] | scheduled | &lt;URL&gt;]
 * products CJ|SMM                         只做品种发现，写 md_source_product
 * download SHFE|SGE|CJ|SMM [...]          只下载原始响应落盘，不入库
 * </pre>
 *
 * <p>在 admin 模块里不会调用本类的 {@code main}；采集由前端页面 / REST 接口
 * 按需触发，执行时落到各 Spider 的采集方法上。</p>
 */
public class CrawlerService {

    public static void main(String[] args) {
        // 应用退出时统一停掉还在跑的 Spider，避免后台线程继续写已关闭的连接池
        SpiderRegistry.installShutdownHook();
        String mode = args.length > 0 ? args[0] : "";

        if ("config".equalsIgnoreCase(mode)) {
            CrawlerConfig.dump();
            return;
        }
        if ("market-status".equalsIgnoreCase(mode)) {
            CloseTimeGuard.printAllStatus();
            return;
        }
        if ("schedule".equalsIgnoreCase(mode)) {
            printSchedule();
            return;
        }
        if ("products".equalsIgnoreCase(mode)) {
            discover(args.length > 1 ? args[1] : null);
            return;
        }
        if ("once".equalsIgnoreCase(mode)) {
            once(args);
            return;
        }
        if ("download".equalsIgnoreCase(mode)) {
            download(args);
            return;
        }
        if (mode.startsWith("http")) {
            ShfeSpider.runOnce(mode); // 旧用法：直接 URL 默认按 SHFE 处理
            return;
        }
        if (mode.matches("\\d{1,2}:\\d{2}")) {
            startSchedules(mode);
            return;
        }
        if (mode.isBlank() && !CrawlerConfig.boolVal("crawler.schedule.enabled", true)) {
            runAllScheduledOnce();
            return;
        }
        startSchedules(mode);
    }


    /** 自检：打印四源的每日触发时刻（schedule 命令）。 */
    private static void printSchedule() {
        System.out.println("=== 四源每日触发时刻 ===");
        for (AbstractSourceScheduler s : AbstractSourceScheduler.all()) {
            List<Integer> list = new ArrayList<>(s.triggerTimes());
            Collections.sort(list);
            StringBuilder sb = new StringBuilder("  " + s.source() + " -> ");
            for (int m : list) {
                sb.append(AbstractSourceScheduler.format(m)).append("  ");
            }
            System.out.println(sb);
        }
        System.out.println("  提示: SHFE/SGE 用 crawler.schedule.times.<源>；"
                + "CJ/SMM 用 crawler.<源>.schedule.open-time / open-delay-minutes / close-delay-minutes");
    }

    // ================================================================
    // once：采集并落库
    // ================================================================

    private static void once(String[] args) {
        // 兼容旧用法：once https://... 或 once 20251031
        if (args.length < 2 || args[1].startsWith("http")
                || args[1].matches("\\d{8}|\\d{4}-\\d{2}-\\d{2}")) {
            String arg = args.length > 1 ? args[1] : null;
            if (arg != null && arg.startsWith("http")) {
                ShfeSpider.runOnce(arg);
            } else if (arg == null || arg.isBlank()) {
                AbstractSourceScheduler.of("SHFE").runOnce();
            } else {
                ShfeSpider.runHistory(arg);
            }
            return;
        }

        String source = args[1].toUpperCase(Locale.ROOT);
        String[] rest = tail(args, 2);
        switch (source) {
            case "SHFE" -> onceShfe(rest);
            case "SGE" -> onceSge(rest);
            case "CJ" -> onceSpot(CjSpider.of("CJ"), rest);
            case "SMM" -> onceSpot(SmmSpider.of("SMM"), rest);
            default -> throw new IllegalArgumentException(
                    "未知数据源 " + args[1] + "，可用 SHFE / SGE / CJ / SMM");
        }
    }

    private static void onceShfe(String[] a) {
        if (a.length == 0 || "all".equalsIgnoreCase(a[0]) || "scheduled".equalsIgnoreCase(a[0])) {
            AbstractSourceScheduler.of("SHFE").runOnce();
        } else if ("today".equalsIgnoreCase(a[0]) || "delay".equalsIgnoreCase(a[0])
                || "realtime".equalsIgnoreCase(a[0])) {
            ShfeSpider.runRealtime();
        } else if ("history".equalsIgnoreCase(a[0])) {
            ShfeSpider.runHistory(a.length > 1 ? a[1] : null);
        } else if (a[0].startsWith("http")) {
            ShfeSpider.runOnce(a[0]);
        } else {
            ShfeSpider.runHistory(a[0]);
        }
    }

    private static void onceSge(String[] a) {
        if (a.length == 0 || "scheduled".equalsIgnoreCase(a[0])) {
            AbstractSourceScheduler.of("SGE").runOnce();
        } else if ("realtime".equalsIgnoreCase(a[0])) {
            SgeSpider.runRealtime();
        } else if ("prev".equalsIgnoreCase(a[0])) {
            SgeSpider.runOnce(SgeSpider.urlForPreviousWorkday());
        } else if (a[0].startsWith("http")) {
            SgeSpider.runOnce(a[0]);
        } else {
            SgeSpider.runDate(a[0]);
        }
    }

    /** 现货源（CJ / SMM）的 once 命令：两个源参数习惯一致，逻辑只有一份。 */
    private static void onceSpot(AbstractSpotSpider spot, String[] a) {
        if (a.length == 0 || "all".equalsIgnoreCase(a[0]) || "night".equalsIgnoreCase(a[0])) {
            spot.runAll(a.length > 1 ? a[1] : null, a.length > 2 ? a[2] : null);
        } else if ("scheduled".equalsIgnoreCase(a[0])) {
            AbstractSourceScheduler.of(spot.name()).runOnce();
        } else if ("one".equalsIgnoreCase(a[0])) {
            spot.runOne(a.length > 1 ? a[1] : null,
                    a.length > 2 ? a[2] : null, a.length > 3 ? a[3] : null);
        } else if (a[0].startsWith("http")) {
            spot.runUrl(a[0]);
        } else {
            spot.runOne(a[0], a.length > 1 ? a[1] : null, a.length > 2 ? a[2] : null);
        }
    }

    // ================================================================
    // download：只落盘，不入库
    // ================================================================

    private static void download(String[] args) {
        if (args.length < 2 || args[1].startsWith("http")) {
            String url = args.length > 1 ? args[1] : ShfeSpider.urlForTodayDelay();
            ShfeSpider.downloadOnly(url);
            return;
        }
        String source = args[1].toUpperCase(Locale.ROOT);
        String[] a = tail(args, 2);
        switch (source) {
            case "SHFE" -> {
                if (a.length > 0 && "history".equalsIgnoreCase(a[0])) {
                    ShfeSpider.downloadHistory(a.length > 1 ? a[1] : null);
                } else {
                    ShfeSpider.downloadTodayDelay();
                }
            }
            case "SGE" -> {
                if (a.length > 0 && "realtime".equalsIgnoreCase(a[0])) {
                    SgeSpider.downloadRealtime();
                } else {
                    SgeSpider.downloadDate(a.length > 0 ? a[0] : null);
                }
            }
            case "CJ" -> CjSpider.of("CJ").downloadOne(
                    a.length > 0 ? a[0] : null,
                    a.length > 1 ? a[1] : null, a.length > 2 ? a[2] : null);
            case "SMM" -> SmmSpider.of("SMM").downloadOne(
                    a.length > 0 ? a[0] : null,
                    a.length > 1 ? a[1] : null, a.length > 2 ? a[2] : null);
            default -> throw new IllegalArgumentException(
                    "未知数据源 " + args[1] + "，可用 SHFE / SGE / CJ / SMM");
        }
    }

    private static void discover(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("products 后请指定 CJ 或 SMM");
        }
        String source = raw.toUpperCase(Locale.ROOT);
        switch (source) {
            case "CJ" -> CjSpider.of("CJ").discoverProducts().forEach(p ->
                    System.out.println("  " + p.getProductId() + " | "
                            + p.getProductName() + " | " + p.getProductGroup()));
            case "SMM" -> SmmSpider.of("SMM").discoverProducts().forEach(p ->
                    System.out.println("  " + p.getProductId() + " | "
                            + p.getProductName() + " | " + p.getProductGroup()));
            default -> throw new IllegalArgumentException("products 只支持 CJ / SMM");
        }
    }

    /** 四个源各跑一次定时决策（盘中采实时、收盘后采收盘、非交易日跳过）。 */
    private static void runAllScheduledOnce() {
        AbstractSourceScheduler.all().forEach(AbstractSourceScheduler::runOnce);
    }

    /**
     * 独立运行时启动四源的定时任务。
     *
     * <p>每个源一个 {@link AbstractSourceScheduler}：它自己解析触发时间、自己决定
     * 到点采什么，本类只负责统一启动、统一挂停机钩子。</p>
     *
     * <p>admin 里不会走这里：采集由 /api/crawl/run 按需触发，
     * 不会留下非守护线程写已关闭的连接池。</p>
     */
    private static void startSchedules(String time) {
        boolean override = time != null && time.matches("\\d{1,2}:\\d{2}");
        int minutesOfDay = override ? AbstractSourceScheduler.minutesOf(time) : 0;

        for (AbstractSourceScheduler schedule : AbstractSourceScheduler.all()) {
            if (override) {
                schedule.startAt(minutesOfDay / 60, minutesOfDay % 60);
            } else {
                schedule.start();
            }
        }
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> AbstractSourceScheduler.all().forEach(AbstractSourceScheduler::stop),
                        "crawler-schedules-stop"));
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String[] tail(String[] args, int from) {
        String[] out = new String[Math.max(0, args.length - from)];
        if (out.length > 0) {
            System.arraycopy(args, from, out, 0, out.length);
        }
        return out;
    }
}
