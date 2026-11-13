package com.zjysky.crawler.scheduler;

import com.zjysky.crawler.CrawlerConfig;
import com.zjysky.crawler.spider.SmmSpider;

import java.util.Set;

/**
 * SMM 上海有色网的定时任务：<b>开盘后 30 分钟内采一次，收盘后采一次</b>。
 *
 * <p>与 {@link CjScheduler} 同一套规则，只是收盘时间不同
 * （{@code crawler.market-close.close-times.SMM}，默认 17:00）：</p>
 * <ol>
 *   <li><b>开盘后 30 分钟内</b>：{@code crawler.smm.schedule.open-time}（默认 09:00）
 *       + {@code crawler.smm.schedule.open-delay-minutes}（默认 30）→ 默认 09:30；</li>
 *   <li><b>收盘后</b>：默认 17:00 + {@code crawler.smm.schedule.close-delay-minutes}（默认 10）
 *       → 默认 17:10 采定稿报价（task_type=NIGHT，幂等覆盖早间价）。</li>
 * </ol>
 */
public final class SmmScheduler extends AbstractSourceScheduler {

    public SmmScheduler() {
        super("SMM",
                // 盘中（开盘后 30 分钟）：抓现货最新报价
                () -> SmmSpider.of("SMM").runAll(null, null),
                // 收盘后：抓定稿报价，幂等覆盖早间那批
                date -> SmmSpider.of("SMM").runAll(date, date));
    }

    @Override
    public Set<Integer> triggerTimes() {
        String cfg = "crawler.smm.schedule.";
        Integer open = parseTime(source(),
                CrawlerConfig.str(cfg + "open-time", "09:00"));
        int openMinutes = (open == null ? 9 * 60 : open)
                + CrawlerConfig.intVal(cfg + "open-delay-minutes", 30);
        int closeMinutes = configuredCloseMinutes()
                + CrawlerConfig.intVal(cfg + "close-delay-minutes", 10);
        return Set.of(openMinutes % (24 * 60), closeMinutes % (24 * 60));
    }
}
