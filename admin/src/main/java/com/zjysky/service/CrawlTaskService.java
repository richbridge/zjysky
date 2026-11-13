package com.zjysky.service;

import com.zjysky.crawler.scheduler.AbstractSourceScheduler;
import com.zjysky.crawler.spider.CjSpider;
import com.zjysky.crawler.spider.SgeSpider;
import com.zjysky.crawler.spider.ShfeSpider;
import com.zjysky.crawler.spider.SmmSpider;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 采集任务服务：把 crawler 的四源采集动作包装成「前端可触发的异步任务」。
 *
 * <p>admin 是前后端分离的 Web 端，不能在 HTTP 线程里直接跑爬虫（一次采集可能几十秒），
 * 因此提交到后台线程池立即返回任务对象，前端轮询 {@code /api/crawl/tasks}。</p>
 *
 * <p>同一个数据源同时只允许一个任务在跑（WebMagic 的 Spider 与落库都是按源串行的），
 * 重复点击直接拒绝，避免重复抓取与无谓的数据库连接占用。</p>
 */
@Service
public class CrawlTaskService {

    /** 前端可提交的任务状态快照（不可变，替换式更新，天然线程安全）。 */
    public record TaskInfo(String id, String source, String mode, String date,
                           String status, String message, String startAt,
                           String endAt, Long costMs) {
    }

    private static final Set<String> SOURCES = Set.of("SHFE", "SGE", "CJ", "SMM");
    private static final Set<String> MODES = Set.of("scheduled", "realtime", "history");

    private final Map<String, TaskInfo> tasks = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "crawler-web-task");
        t.setDaemon(true);
        return t;
    });

    /** 提交一次采集；同一源正在采集时抛 IllegalStateException（Controller 转 409）。 */
    public TaskInfo submit(String source, String mode, String date) {
        String src = normalizeSource(source);
        String normalizedMode = mode == null || mode.isBlank()
                ? "scheduled" : mode.trim().toLowerCase(Locale.ROOT);
        if (!MODES.contains(normalizedMode)) {
            throw new IllegalArgumentException("未知采集模式 " + mode
                    + "，可用 scheduled / realtime / history");
        }
        String day = normalizedMode.equals("history") ? normalizeDate(date) : (date == null ? "" : date.trim());
        if (!running.add(src)) {
            throw new IllegalStateException(src + " 已有采集任务在执行，请等它结束再试");
        }

        long start = System.currentTimeMillis();
        String id = src.toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID().toString().substring(0, 8);
        TaskInfo created = new TaskInfo(id, src, normalizedMode, day, "RUNNING",
                "任务已提交，正在采集…", LocalDateTime.now().toString(), null, null);
        tasks.put(id, created);

        executor.submit(() -> {
            try {
                collect(src, normalizedMode, day);
                finish(id, "SUCCESS", "采集完成，结果可在价格数据与采集日志中查看", start);
            } catch (Exception e) {
                finish(id, "FAILED", "采集失败：" + e.getMessage(), start);
            } finally {
                running.remove(src);
            }
        });
        return created;
    }

    /** 最近的任务，按提交时间倒序。 */
    public List<TaskInfo> recent(int limit) {
        return tasks.values().stream()
                .sorted(Comparator.comparing(TaskInfo::startAt).reversed())
                .limit(Math.max(1, Math.min(limit, 50)))
                .toList();
    }

    /** 当前正在跑的源，供前端按钮置灰。 */
    public Set<String> runningSources() {
        return Set.copyOf(running);
    }

    private void finish(String id, String status, String message, long start) {
        TaskInfo old = tasks.get(id);
        if (old == null) {
            return;
        }
        tasks.put(id, new TaskInfo(old.id(), old.source(), old.mode(), old.date(), status, message,
                old.startAt(), LocalDateTime.now().toString(), System.currentTimeMillis() - start));
    }

    /** 真正的采集分派：只调用 crawler 的 Spider / 调度入口，不复制任何采集逻辑。 */
    private void collect(String source, String mode, String date) {
        switch (mode) {
            case "scheduled" -> AbstractSourceScheduler.of(source).runOnce();
            case "realtime" -> collectRealtime(source);
            case "history" -> collectHistory(source, date);
            default -> throw new IllegalArgumentException("不支持的模式 " + mode);
        }
    }

    private void collectRealtime(String source) {
        switch (source) {
            case "SHFE" -> ShfeSpider.runRealtime();
            case "SGE" -> SgeSpider.runRealtime();
            // 现货源的「实时」= 抓现货最新报价（定时调度的盘中动作也是它）
            case "CJ" -> CjSpider.of("CJ").runAll(null, null);
            case "SMM" -> SmmSpider.of("SMM").runAll(null, null);
            default -> throw new IllegalArgumentException("未知数据源 " + source);
        }
    }

    private void collectHistory(String source, String date) {
        switch (source) {
            case "SHFE" -> ShfeSpider.runHistory(date);
            case "SGE" -> SgeSpider.runDate(date);
            case "CJ" -> CjSpider.of("CJ").runAll(date, date);
            case "SMM" -> SmmSpider.of("SMM").runAll(date, date);
            default -> throw new IllegalArgumentException("未知数据源 " + source);
        }
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("数据源不能为空");
        }
        String src = source.trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(src)) {
            throw new IllegalArgumentException("未知数据源 " + source + "，可用 SHFE / SGE / CJ / SMM");
        }
        return src;
    }

    /** 统一成 spider 都能吃的 yyyy-MM-dd。 */
    private static String normalizeDate(String date) {
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("指定日期采集必须传 date（yyyy-MM-dd）");
        }
        String d = date.trim();
        try {
            if (d.matches("\\d{8}")) {
                return LocalDate.parse(d, DateTimeFormatter.BASIC_ISO_DATE).toString();
            }
            return LocalDate.parse(d).toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式不对：" + date + "，应为 yyyy-MM-dd");
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
