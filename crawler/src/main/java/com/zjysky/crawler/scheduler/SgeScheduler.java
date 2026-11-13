package com.zjysky.crawler.scheduler;

import com.zjysky.crawler.CrawlerConfig;
import com.zjysky.crawler.spider.SgeSpider;

import java.util.Set;

/**
 * SGE 上金所的定时任务：盘中采延时行情页（REALTIME），
 * 收盘后采每日行情 Excel（QUOTE，定稿后覆盖实时行）。
 *
 * <p>触发时间：{@code crawler.schedule.times.sge}，默认 10:00 与 15:40。</p>
 */
public final class SgeScheduler extends AbstractSourceScheduler {

    /** 无显式配置时的默认触发点。 */
    private static final String DEFAULT_TIMES = "10:00,15:40";

    public SgeScheduler() {
        super("SGE", SgeSpider::runRealtime, date -> SgeSpider.runDate(date));
    }

    @Override
    public Set<Integer> triggerTimes() {
        String raw = CrawlerConfig.str(SCHEDULE_CFG + "times.sge", DEFAULT_TIMES);
        return parseTimes(source(), raw, DEFAULT_TIMES);
    }
}
