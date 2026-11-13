package com.zjysky.crawler.scheduler;

import com.zjysky.crawler.CrawlerConfig;
import com.zjysky.crawler.spider.ShfeSpider;

import java.util.Set;

/**
 * SHFE 上期所的定时任务：盘中采当日延时行情（REALTIME），
 * 收盘后采历史日行情（QUOTE，定稿后覆盖实时行）。
 *
 * <p>触发时间：{@code crawler.schedule.times.shfe}，默认 10:00 与 15:40。</p>
 */
public final class ShfeScheduler extends AbstractSourceScheduler {

    /** 无显式配置时的默认触发点。 */
    private static final String DEFAULT_TIMES = "10:00,15:40";

    public ShfeScheduler() {
        super("SHFE", ShfeSpider::runRealtime, ShfeSpider::runHistory);
    }

    @Override
    public Set<Integer> triggerTimes() {
        String raw = CrawlerConfig.str(SCHEDULE_CFG + "times.shfe", DEFAULT_TIMES);
        return parseTimes(source(), raw, DEFAULT_TIMES);
    }
}
