package com.zjysky.crawler.scheduler;

import com.zjysky.crawler.spider.ShfeSpider;

/**
 * SHFE 上期所专用 Scheduler（四大组件之一：Scheduler）。
 *
 * <p>现在是一个薄壳：URL 队列 + 去重继承 {@link SourceScheduler}，
 * 每日定时触发的线程池逻辑继承 {@link TimedSourceScheduler}，
 * 本类只负责告诉基类「到点要调用 {@link ShfeSpider#runScheduled()}」。</p>
 *
 * <p>和 {@link SgeScheduler} / {@link CjScheduler} / {@link SmmScheduler} 完全对称，
 * 四个源在 scheduler 文件夹下各有一个文件，和 downloader / processor / pipeline 一致。</p>
 *
 * <p><b>独立运行</b>时才调用 {@link #startScheduled()}；admin 里由 Spring
 * {@code @Scheduled} 接管，不启动这里的非守护定时线程。</p>
 */
public class ShfeScheduler extends TimedSourceScheduler {

    public ShfeScheduler() {
        super("shfe", ShfeSpider::runScheduled);
    }
}
