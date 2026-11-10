package com.zjysky.crawler.scheduler;

import com.zjysky.crawler.config.CrawlerConfig;
import com.zjysky.crawler.processor.ShfeProcessor;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.scheduler.QueueScheduler;
import us.codecraft.webmagic.scheduler.Scheduler;
import us.codecraft.webmagic.scheduler.component.HashSetDuplicateRemover;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 四大组件之一：Scheduler（实现 WebMagic 的 Scheduler 接口）。
 * 双重职责：
 *  1) URL 队列与去重 —— 委托 QueueScheduler + HashSetDuplicateRemover，供 Spider.setScheduler() 使用；
 *  2) 设置定时爬取 —— 每日指定时刻触发一次完整采集流程。
 *
 * <p>定时时刻来自 application.yml 的 {@code crawler.schedule.time}（HH:mm），
 * 时区来自 {@code crawler.schedule.zone}；命令行传 HH:mm 可临时覆盖。</p>
 */
public class ShfeScheduler implements Scheduler {

    private static final String SCHEDULE = "crawler.schedule.";

    private final QueueScheduler delegate = new QueueScheduler();
    private ScheduledExecutorService timer;

    public ShfeScheduler() {
        delegate.setDuplicateRemover(new HashSetDuplicateRemover());
    }

    @Override
    public void push(Request request, Task task) {
        delegate.push(request, task);
    }

    @Override
    public Request poll(Task task) {
        return delegate.poll(task);
    }

    /** 按 application.yml 的 crawler.schedule.time 启动定时爬取 */
    public void startScheduled() {
        String[] hm = CrawlerConfig.str(SCHEDULE + "time", "08:00").split(":");
        int hour = Integer.parseInt(hm[0].trim());
        int minute = hm.length > 1 ? Integer.parseInt(hm[1].trim()) : 0;
        startScheduled(hour, minute);
    }

    /** 设置定时爬取：每日 hour:minute 触发 */
    public void startScheduled(int hour, int minute) {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shfe-scheduler");
            t.setDaemon(false);
            return t;
        });
        long delay = delayMinutes(hour, minute);
        timer.scheduleAtFixedRate(this::crawlToday, delay, 24L * 60L, TimeUnit.MINUTES);
        System.out.println(">>> [Scheduler] 定时爬取已启动：每日 "
                + String.format("%02d:%02d", hour, minute)
                + " " + CrawlerConfig.str(SCHEDULE + "zone", "Asia/Shanghai")
                + "，距首次触发约 " + delay + " 分钟");
    }

    /** 停止定时爬取 */
    public void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
            System.out.println(">>> [Scheduler] 定时爬取已停止");
        }
    }

    private void crawlToday() {
        String url = ShfeProcessor.urlForToday();
        System.out.println(">>> [Scheduler] 定时触发: " + url);
        try {
            ShfeProcessor.runOnce(url);
        } catch (Exception e) {
            System.err.println(">>> [Scheduler] 本次采集异常: " + e.getMessage());
        }
    }

    /** 距今日目标时刻的分钟数；已过则顺延 24 小时 */
    private static long delayMinutes(int hour, int minute) {
        ZoneId zone = ZoneId.of(CrawlerConfig.str(SCHEDULE + "zone", "Asia/Shanghai"));
        LocalTime now = LocalTime.now(zone);
        long delay = (hour * 60L + minute) - (now.getHour() * 60L + now.getMinute());
        return delay <= 0 ? delay + 24L * 60L : delay;
    }
}
