package com.zjysky.crawler.scheduler;

import com.zjysky.crawler.CrawlerConfig;
import com.zjysky.crawler.guard.CloseTimeGuard;
import com.zjysky.crawler.guard.CollectGuard;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 四大组件之一：Scheduler 的共用基类，四个源各有一个子类
 * （{@link ShfeScheduler} / {@link SgeScheduler} / {@link CjScheduler} / {@link SmmScheduler}）。
 *
 * <p>一个实例 = 本源的定时任务 + 一个非守护定时线程，负责两件事：</p>
 * <ol>
 *   <li><b>什么时候跑</b>：子类给出每日触发点集合（{@link #triggerTimes()}）；</li>
 *   <li><b>到点采什么</b>：共用决策流程（{@link #runOnce()}）——
 *       交易日？盘中采实时/最新报价 → 收盘后采定稿数据 → 非交易日跳过 →
 *       收盘数据当天只采一次（CollectGuard 幂等）。</li>
 * </ol>
 *
 * <p>本类只管「什么时候采什么」；具体怎么下载、怎么解析、怎么落库
 * 全由 spider / downloader / processor / pipeline 负责。</p>
 *
 * <p><b>只在独立运行时启动定时线程</b>（{@code java -jar crawler.jar}）。在 admin 里
 * 由前端接口按需触发（{@code /api/crawl/run}），不启动本类——非守护线程不受
 * Spring 生命周期管理，应用关闭后还会继续写已关闭的连接池。</p>
 */
public abstract class AbstractSourceScheduler {

    protected static final String SCHEDULE_CFG = "crawler.schedule.";
    private static final String CLOSE_CFG = "crawler.market-close.";

    /** 大写源名，如 SHFE，用于日志、配置前缀和 CloseTimeGuard。 */
    private final String source;

    /** 盘中触发动作（实时/最新报价）；没有实时口径的源为 null。 */
    private final Runnable realtimeAction;

    /** 收盘触发动作，参数是交易日 yyyy-MM-dd。 */
    private final Consumer<String> closeAction;

    /** 到点执行一次完整决策。 */
    private final Runnable fireOnce = this::runOnce;

    private ScheduledExecutorService timer;

    protected AbstractSourceScheduler(String source, Runnable realtimeAction,
                                      Consumer<String> closeAction) {
        this.source = source.toUpperCase(Locale.ROOT);
        this.realtimeAction = realtimeAction;
        this.closeAction = closeAction;
    }

    // ================================================================
    // 四源登记表
    // ================================================================

    /** 顺序固定为 SHFE / SGE / CJ / SMM，方便日志对照。 */
    private static final List<AbstractSourceScheduler> ALL = List.of(
            new ShfeScheduler(), new SgeScheduler(), new CjScheduler(), new SmmScheduler());

    /** 全部定时任务。 */
    public static List<AbstractSourceScheduler> all() {
        return ALL;
    }

    /** 按源名取定时任务，大小写不敏感；未知源直接报错。 */
    public static AbstractSourceScheduler of(String source) {
        return ALL.stream()
                .filter(s -> s.source().equalsIgnoreCase(source))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "未知数据源 " + source + "，可用 SHFE / SGE / CJ / SMM"));
    }

    /** 本源名（大写），如 SHFE。 */
    public final String source() {
        return source;
    }

    // ================================================================
    // 触发时间：由子类按源给出（全部来自配置，无写死值）
    // ================================================================

    /**
     * 本源每天的触发时刻（当日分钟数集合）。
     *
     * <p>SHFE / SGE：{@code crawler.schedule.times.<源>}，默认 10:00 与 15:40；</p>
     * <p>CJ / SMM：开盘后 30 分钟内采一次（{@code crawler.<源>.schedule.open-time}
     * + {@code open-delay-minutes}），收盘后采一次（{@code crawler.market-close.close-times.<源>}
     * + {@code close-delay-minutes}）。</p>
     */
    public abstract Set<Integer> triggerTimes();

    /** 解析逗号分隔的 HH:mm 列表；全部非法时退回默认值。 */
    protected static Set<Integer> parseTimes(String source, String raw, String defaultRaw) {
        Set<Integer> minutes = new LinkedHashSet<>();
        for (String part : raw.split("[,;，；\\s]+")) {
            Integer minute = parseTime(source, part);
            if (minute != null) {
                minutes.add(minute);
            }
        }
        if (minutes.isEmpty()) {
            for (String part : defaultRaw.split(",")) {
                minutes.add(parseTime(source, part));
            }
        }
        return minutes;
    }

    /** 单个 HH:mm 解析；非法返回 null 并打一行提示。 */
    protected static Integer parseTime(String source, String part) {
        if (part == null || part.isBlank()) {
            return null;
        }
        if (!part.matches("\\d{1,2}:\\d{2}")) {
            System.err.println(">>> [Schedule:" + source + "] 触发时间格式应为 HH:mm，已忽略: " + part);
            return null;
        }
        String[] hm = part.split(":");
        int hour = Integer.parseInt(hm[0]);
        int minute = Integer.parseInt(hm[1]);
        if (hour > 23 || minute > 59) {
            System.err.println(">>> [Schedule:" + source + "] 触发时间超出范围 0-23:0-59，已忽略: " + part);
            return null;
        }
        return hour * 60 + minute;
    }

    /** HH:mm → 当日分钟数；非法直接抛错（配置错误应当被发现而不是静默跳过）。 */
    public static int minutesOf(String hhmm) {
        Integer minute = parseTime("命令行", hhmm);
        if (minute == null) {
            throw new IllegalArgumentException("触发时间格式应为 HH:mm，收到: " + hhmm);
        }
        return minute;
    }

    /** 当日分钟数 → HH:mm，供日志与状态输出使用。 */
    public static String format(int minutesOfDay) {
        return String.format("%02d:%02d", minutesOfDay / 60, minutesOfDay % 60);
    }

    // ================================================================
    // 生命周期：独立运行时由 CrawlerService 调用
    // ================================================================

    /** 按子类给出的触发时间启动每日定时。 */
    public final void start() {
        start(triggerTimes());
    }

    /** 命令行覆盖：所有源统一只用这一个每日触发点（{@code java -jar crawler.jar 09:30}）。 */
    public final void startAt(int hour, int minute) {
        start(Set.of(hour * 60 + minute));
    }

    /** 启动定时触发；每个触发点相互独立，到点各自执行一次完整决策。 */
    private synchronized void start(Set<Integer> minutesOfDay) {
        if (timer != null) {
            return;
        }
        Set<Integer> points = (minutesOfDay == null || minutesOfDay.isEmpty())
                ? Set.of(8 * 60) : minutesOfDay;

        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, source.toLowerCase(Locale.ROOT) + "-schedule");
            thread.setDaemon(false);
            return thread;
        });

        StringBuilder plan = new StringBuilder();
        for (int minutes : points) {
            long delay = delayMinutes(minutes);
            timer.scheduleAtFixedRate(fireOnce, delay, TimeUnit.DAYS.toMinutes(1),
                    TimeUnit.MINUTES);
            if (plan.length() > 0) {
                plan.append(", ");
            }
            plan.append(format(minutes)).append("（").append(delay).append(" 分钟后首次）");
        }
        System.out.println(">>> [Schedule:" + source + "] 定时触发已启动：每日 " + plan
                + " " + zone());
    }

    /** 停止触发（关闭应用时调用，避免线程泄漏）。 */
    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
            System.out.println(">>> [Schedule:" + source + "] 定时触发已停止");
        }
    }

    /** 距今日目标时刻还有多少分钟；已过则顺延 24 小时。 */
    private static long delayMinutes(int minutesOfDay) {
        LocalTime now = LocalTime.now(zone());
        long nowMinutes = now.getHour() * 60L + now.getMinute();
        long delay = minutesOfDay - nowMinutes;
        return delay <= 0 ? delay + TimeUnit.DAYS.toMinutes(1) : delay;
    }

    private static ZoneId zone() {
        return ZoneId.of(CrawlerConfig.str(SCHEDULE_CFG + "zone", "Asia/Shanghai"));
    }

    // ================================================================
    // 一次触发：所有源共用的决策流程
    // ================================================================

    /**
     * 执行一次定时触发。手动排查时也可以直接调用它，
     * 效果与「定时器到点」完全一致（会走守卫和幂等）。
     */
    public void runOnce() {
        LocalDateTime now = CloseTimeGuard.now();
        System.out.println(">>> [" + source + "] 定时触发 " + now
                + " | " + CloseTimeGuard.status(source, now));

        if (!CloseTimeGuard.enabled()) {
            // 守卫关掉 = 手动模式：不看时间，实时与收盘各采一次
            runRealtime();
            closeAction.accept(closeDate(now));
            return;
        }

        if (CloseTimeGuard.isIntraday(source, now)) {
            if (realtimeAction != null) {
                System.out.println(">>> [" + source + "] 盘中，采集实时/最新报价");
                realtimeAction.run();
            } else {
                System.out.println(">>> [" + source + "] 价格尚未定稿，等待收盘 "
                        + CloseTimeGuard.closeTime(source) + " 后采集");
            }
            return;
        }

        if (!CloseTimeGuard.shouldCollectHistory(source, now)) {
            System.out.println(">>> [" + source + "] 非交易日，跳过本次定时采集");
            return;
        }

        String tradeDate = closeDate(now);
        if (CollectGuard.alreadyClosed(source, tradeDate)) {
            System.out.println(">>> [" + source + "] " + tradeDate
                    + " 收盘数据今天已采集过，跳过本次重复触发");
            return;
        }

        System.out.println(">>> [" + source + "] 已收盘，采集 " + tradeDate + " 收盘数据");
        closeAction.accept(tradeDate);
        // 只有完整跑完「下载 → 解析 → 落库」才标记；中途失败下次触发会自动重试
        CollectGuard.markClosed(source, tradeDate);
    }

    private void runRealtime() {
        if (realtimeAction != null) {
            realtimeAction.run();
        }
    }

    /**
     * 这次该采哪个交易日的收盘数据。
     *
     * <p>默认取「最近一个已收盘交易日」；把 {@code crawler.<源>.history-auto-date}
     * 配成 false 则改用 {@code history-default-date} / {@code default-date} 固定日期。</p>
     */
    private String closeDate(LocalDateTime now) {
        String cfg = "crawler." + source.toLowerCase(Locale.ROOT) + ".";
        if (CrawlerConfig.boolVal(cfg + "history-auto-date", true)) {
            return CloseTimeGuard.lastClosedTradeDate(source, now);
        }
        String fixed = CrawlerConfig.str(cfg + "history-default-date",
                CrawlerConfig.str(cfg + "default-date", ""));
        return fixed.isBlank() ? CloseTimeGuard.lastClosedTradeDate(source, now) : fixed;
    }

    /** 收盘配置里的时刻（crawler.market-close.close-times.<源>）。 */
    protected final int configuredCloseMinutes() {
        String raw = CrawlerConfig.str(CLOSE_CFG + "close-times." + source, "15:00");
        Integer minute = parseTime(source, raw);
        return minute == null ? 15 * 60 : minute;
    }
}
