package com.zjysky.crawler.scheduler;

import com.zjysky.crawler.CrawlerConfig;
import com.zjysky.crawler.spider.CjSpider;

import java.util.Set;

/**
 * CJ 长江有色的定时任务：<b>开盘后 30 分钟内采一次，收盘后采一次</b>。
 *
 * <p>现货报价按日发布，一天两个触发点：</p>
 * <ol>
 *   <li><b>开盘后 30 分钟内</b>：{@code crawler.cj.schedule.open-time}（默认 09:00）
 *       + {@code crawler.cj.schedule.open-delay-minutes}（默认 30）→ 默认 09:30 采当日早间报价；</li>
 *   <li><b>收盘后</b>：{@code crawler.market-close.close-times.CJ}（默认 15:00）
 *       + {@code crawler.cj.schedule.close-delay-minutes}（默认 10）→ 默认 15:10 采定稿报价。
 *       两次采集的 task_type 相同（QUOTE），收盘后那批按唯一键幂等覆盖早间价。</li>
 * </ol>
 *
 * <p>两个触发点都走 {@code AbstractSourceScheduler.runOnce()} 的共用决策：
 * 09:30 处于盘中 → 执行「实时」动作（抓最新报价）；15:10 已收盘 →
 * 采收盘数据并做当日幂等标记（CollectGuard）。</p>
 */
public final class CjScheduler extends AbstractSourceScheduler {

    public CjScheduler() {
        super("CJ",
                // 盘中（开盘后 30 分钟）：抓现货最新报价
                () -> CjSpider.of("CJ").runAll(null, null),
                // 收盘后：抓定稿报价，幂等覆盖早间那批
                date -> CjSpider.of("CJ").runAll(date, date));
    }

    @Override
    public Set<Integer> triggerTimes() {
        String cfg = "crawler.cj.schedule.";
        // 开盘时间 + 开盘延迟（默认 09:00 + 30 = 09:30）
        Integer open = parseTime(source(),
                CrawlerConfig.str(cfg + "open-time", "09:00"));
        int openMinutes = (open == null ? 9 * 60 : open)
                + CrawlerConfig.intVal(cfg + "open-delay-minutes", 30);
        // 收盘时间（crawler.market-close.close-times.CJ，默认 15:00）+ 收盘延迟（默认 10）
        int closeMinutes = configuredCloseMinutes()
                + CrawlerConfig.intVal(cfg + "close-delay-minutes", 10);
        return Set.of(openMinutes % (24 * 60), closeMinutes % (24 * 60));
    }
}
