package com.zjysky.crawler.guard;

import com.zjysky.crawler.CrawlerConfig;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 收盘采集的「当天别重复采」守卫（进程内）。
 *
 * <p><b>为什么需要它</b>：定时调度支持一天多个触发点。盘中触发采实时没问题——
 * 价格一直在变，就是要多次采样；但收盘数据是定稿的，15:40 成功采过之后，
 * 16:40 再触发就是白下载一份一模一样的文件。本守卫记录「哪个源哪一天已经采过」。</p>
 *
 * <p><b>只拦收盘，不拦实时</b>：只被 {@code SourceSchedule#runOnce()} 的收盘分支调用。
 * 手动命令（{@code once SHFE history 20251031}）不走这里，想重采随时可以。</p>
 *
 * <p><b>两个可调参数</b>（都在 {@code crawler.market-close.*} 下）：</p>
 * <ol>
 *   <li>{@code collect-once-per-day}：默认 true；设 false 则每次触发都照常采集；</li>
 *   <li>{@code collect-once-ttl-minutes}：默认 0 = 全天有效（跨天自动清理）；
 *       设正数则只在该分钟数窗口内拦截，窗口过后允许重试。</li>
 * </ol>
 *
 * <p>状态只存内存：重启后清空，最多导致重启后多采一次（幂等 upsert，无害）。</p>
 */
public final class CollectGuard {

    /** key = 源|CLOSE|交易日 → 该次采集完成时刻（毫秒）。 */
    private static final Map<String, Long> DONE = new ConcurrentHashMap<>();

    /** 跨天清理标记：日期变了就整表清空，避免长期运行内存缓慢增长。 */
    private static volatile String currentDay = null;

    private CollectGuard() {
    }

    /** 是否开启「收盘数据一天只采一次」，crawler.market-close.collect-once-per-day，默认 true。 */
    public static boolean enabled() {
        return CrawlerConfig.boolVal("crawler.market-close.collect-once-per-day", true);
    }

    /**
     * 收盘采集前调用：该源该交易日是否已经采过收盘？
     *
     * @return true = 已经采过，本次应跳过；false = 没采过（或超出时间窗），可以采
     */
    public static boolean alreadyClosed(String source, String tradeDate) {
        if (!enabled()) {
            return false;
        }
        rolloverIfNeeded();
        String key = key(source, tradeDate);
        Long at = DONE.get(key);
        if (at == null) {
            return false;
        }
        long ttl = ttlMinutes();
        if (ttl > 0 && System.currentTimeMillis() - at >= ttl * 60_000L) {
            // 超出时间窗：当作没采过，允许重试（顺手清掉旧记录）。
            // ttl<=0 表示全天有效，不按时间过期，跨天统一清理。
            DONE.remove(key);
            return false;
        }
        return true;
    }

    /**
     * 收盘采集成功完成后调用，记下这次采集。
     * 只有走完「下载 → 解析 → 落库」且没抛异常才记，失败下次触发会自动重试。
     */
    public static void markClosed(String source, String tradeDate) {
        if (!enabled()) {
            return;
        }
        rolloverIfNeeded();
        DONE.put(key(source, tradeDate), System.currentTimeMillis());
    }

    /**
     * 守卫生效的时间窗（分钟），crawler.market-close.collect-once-ttl-minutes。
     * 默认 0 = 全天有效（「每天只采集一次」，跨天由 {@link #rolloverIfNeeded} 清理）。
     */
    private static long ttlMinutes() {
        return CrawlerConfig.longVal("crawler.market-close.collect-once-ttl-minutes", 0);
    }

    /** 跨天清理：日期一变就清掉旧记录（key 里带交易日，跨天本就失效，清一下更省内存）。 */
    private static void rolloverIfNeeded() {
        String today = LocalDate.now(ZoneId.of(
                CrawlerConfig.str("crawler.market-close.zone", "Asia/Shanghai"))).toString();
        if (today.equals(currentDay)) {
            return;
        }
        synchronized (CollectGuard.class) {
            if (today.equals(currentDay)) {
                return;
            }
            long ttl = ttlMinutes();
            Iterator<String> it = DONE.keySet().iterator();
            while (it.hasNext()) {
                String key = it.next();
                Long at = DONE.get(key);
                // ttl<=0（全天有效）时只清跨天前的旧记录；否则清超过时间窗的
                if (at == null || !key.contains(today)
                        || (ttl > 0 && System.currentTimeMillis() - at >= ttl * 60_000L)) {
                    it.remove();
                }
            }
            currentDay = today;
        }
    }

    private static String key(String source, String tradeDate) {
        String src = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        String date = tradeDate == null ? "" : tradeDate.trim();
        return src + "|CLOSE|" + date;
    }
}
