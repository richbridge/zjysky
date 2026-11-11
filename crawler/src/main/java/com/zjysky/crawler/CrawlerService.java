package com.zjysky.crawler;

import com.zjysky.crawler.config.CrawlerConfig;
import com.zjysky.crawler.scheduler.CjScheduler;
import com.zjysky.crawler.scheduler.SgeScheduler;
import com.zjysky.crawler.scheduler.ShfeScheduler;
import com.zjysky.crawler.scheduler.SmmScheduler;
import com.zjysky.crawler.scheduler.TimedSourceScheduler;
import com.zjysky.crawler.spider.CjSpider;
import com.zjysky.crawler.spider.SgeSpider;
import com.zjysky.crawler.spider.ShfeSpider;
import com.zjysky.crawler.spider.SmmSpider;
import com.zjysky.crawler.support.CloseTimeGuard;
import com.zjysky.crawler.support.SpiderRegistry;

import java.util.List;
import java.util.Locale;

/**
 * 统一启动入口。配置统一在 {@code crawler/src/main/resources/application.yml}。
 *
 * <p>项目结构（按职责分文件夹，一个文件夹只做一类事）：</p>
 * <pre>
 * crawler/src/main/java/com/zjysky/crawler/
 *   ├── config/      读配置、连数据库
 *   ├── model/       四张价格表 + 品种字典对应的 Java 类（Lombok）
 *   ├── mapper/      MyBatis-Plus Mapper 接口
 *   ├── support/     数字/日期/表结构/关联合约等共用小工具
 *   ├── spider/      ★ 总指挥：把下面四个组件组装起来并启动
 *   ├── downloader/  ① 下载 JSON / Excel / HTML（原始响应落盘）
 *   ├── processor/   ② 解析内容（JSON / Excel / HTML → Java 对象）
 *   ├── scheduler/   ③ 管理待抓 URL 队列 / 独立运行时定时触发
 *   └── pipeline/    ④ 字段自检 + 幂等写库
 * </pre>
 *
 * <p><b>常用命令</b>（source 取 SHFE / SGE / CJ / SMM，大小写不敏感）：</p>
 * <pre>
 * config                                  打印生效配置（密码打码）
 * market-status                           打印四源收盘时间 / 当前该采实时还是历史
 *
 * once SHFE                               按收盘守卫决定：盘中采实时、收盘后采历史
 * once SHFE realtime                      强制只采当日实时（task_type=REALTIME）
 * once SHFE history 20251031              只采历史日行情 kx20251031.dat
 * once SHFE scheduled                     走一次收盘守卫（和定时任务同样逻辑）
 * once SHFE &lt;完整URL&gt;                    自动识别历史 / 当日
 *
 * once SGE realtime                       采当日延时行情 HTML（task_type=REALTIME）
 * once SGE 2025-11-10                     采上金所指定日收盘 Excel（QUOTE）
 * once SGE prev                           采上一工作日（仅跳过周末）
 * once SGE scheduled                      走一次收盘守卫
 *
 * once CJ                                 发现全部 CJ 合约并逐个采集
 * once CJ all 2025-10-06 2025-11-06       同上，显式指定日期参数
 * once CJ one 201102250293                只采一个合约（调试用）
 * once SMM                                晚上采集：发现全部 SMM 合约并逐个采集
 * once SMM one 201102250376               只采一个 SMM 合约（调试用）
 * once CJ|SMM scheduled                   走一次收盘守卫（未到收盘不采）
 *
 * products CJ|SMM                         只做品种发现，写 md_source_product
 * download SHFE|SGE realtime              只下载当日实时原始响应落盘
 * download SHFE|SGE|CJ|SMM [...]          只下载原始响应落盘，不入库
 *
 * 09:30                                   独立运行时覆盖定时时刻（默认取 yml）
 * </pre>
 *
 * <p>在 admin 模块里不会调用本类的 {@code main}；Spring 通过
 * {@code CrawlerTaskService} 调用各 {@code *Spider.run*()} 静态方法。</p>
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
            startScheduler(mode);
            return;
        }
        if (mode.isBlank() && !CrawlerConfig.boolVal("crawler.schedule.enabled", true)) {
            runAllScheduledOnce();
            return;
        }
        startScheduler(mode);
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
                ShfeSpider.runScheduled();
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
            case "CJ" -> onceCj(rest);
            case "SMM" -> onceSmm(rest);
            default -> throw new IllegalArgumentException(
                    "未知数据源 " + args[1] + "，可用 SHFE / SGE / CJ / SMM");
        }
    }

    private static void onceShfe(String[] a) {
        if (a.length == 0 || "all".equalsIgnoreCase(a[0]) || "scheduled".equalsIgnoreCase(a[0])) {
            ShfeSpider.runScheduled();
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
            SgeSpider.runScheduled();
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

    private static void onceCj(String[] a) {
        if (a.length == 0 || "all".equalsIgnoreCase(a[0])) {
            String from = a.length > 1 ? a[1] : null;
            String to = a.length > 2 ? a[2] : null;
            CjSpider.runAll(from, to);
        } else if ("scheduled".equalsIgnoreCase(a[0])) {
            CjSpider.runScheduled();
        } else if ("one".equalsIgnoreCase(a[0])) {
            CjSpider.runOne(a.length > 1 ? a[1] : null,
                    a.length > 2 ? a[2] : null, a.length > 3 ? a[3] : null);
        } else if (a[0].startsWith("http")) {
            CjSpider.runUrl(a[0]);
        } else {
            CjSpider.runOne(a[0], a.length > 1 ? a[1] : null, a.length > 2 ? a[2] : null);
        }
    }

    private static void onceSmm(String[] a) {
        if (a.length == 0 || "night".equalsIgnoreCase(a[0]) || "all".equalsIgnoreCase(a[0])) {
            String from = a.length > 1 ? a[1] : null;
            String to = a.length > 2 ? a[2] : null;
            SmmSpider.runAll(from, to);
        } else if ("scheduled".equalsIgnoreCase(a[0])) {
            SmmSpider.runScheduled();
        } else if ("one".equalsIgnoreCase(a[0])) {
            SmmSpider.runOne(a.length > 1 ? a[1] : null,
                    a.length > 2 ? a[2] : null, a.length > 3 ? a[3] : null);
        } else if (a[0].startsWith("http")) {
            SmmSpider.runUrl(a[0]);
        } else {
            SmmSpider.runOne(a[0], a.length > 1 ? a[1] : null, a.length > 2 ? a[2] : null);
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
            case "CJ" -> CjSpider.downloadOne(a.length > 0 ? a[0] : null,
                    a.length > 1 ? a[1] : null, a.length > 2 ? a[2] : null);
            case "SMM" -> SmmSpider.downloadOne(a.length > 0 ? a[0] : null,
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
            case "CJ" -> CjSpider.discoverProducts().forEach(p ->
                    System.out.println("  " + p.getProductId() + " | "
                            + p.getProductName() + " | " + p.getProductGroup()));
            case "SMM" -> SmmSpider.discoverProducts().forEach(p ->
                    System.out.println("  " + p.getProductId() + " | "
                            + p.getProductName() + " | " + p.getProductGroup()));
            default -> throw new IllegalArgumentException("products 只支持 CJ / SMM");
        }
    }

    /** 四个源各跑一次 runScheduled()，由各自的 CloseTimeGuard 决定采什么。 */
    private static void runAllScheduledOnce() {
        ShfeSpider.runScheduled();
        SgeSpider.runScheduled();
        CjSpider.runScheduled();
        SmmSpider.runScheduled();
    }

    /**
     * 独立运行时启动四源的 Scheduler。
     *
     * <p>四个 scheduler 文件夹下的类在这里全部用上，每个都只负责
     * 「每天几点触发一次 + 到点调哪个 Spider」；真正的采集范围由
     * 各 Spider 的 runScheduled() 按收盘时间决定。</p>
     *
     * <p>admin 里不会走这里：Spring 的 {@code @Scheduled} 接管定时，
     * 不会留下非守护线程写已关闭的连接池。</p>
     */
    private static void startScheduler(String time) {
        List<TimedSourceScheduler> schedulers = List.of(
                new ShfeScheduler(),
                new SgeScheduler(),
                new CjScheduler(),
                new SmmScheduler());
        for (TimedSourceScheduler scheduler : schedulers) {
            if (time != null && time.matches("\\d{1,2}:\\d{2}")) {
                String[] hm = time.split(":");
                scheduler.startScheduled(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
            } else {
                scheduler.startScheduled();
            }
        }
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> schedulers.forEach(TimedSourceScheduler::stop),
                        "crawler-schedulers-stop"));
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
