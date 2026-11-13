package com.zjysky.crawler.spider;

import us.codecraft.webmagic.Spider;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一管理正在运行的 WebMagic Spider。
 *
 * <p>为什么需要它：WebMagic 的 {@code spider.run()} 内部会起自己的抓取线程池。
 * 如果应用（尤其是 Spring Boot / DevTools）在任务跑到一半时关闭，后台线程可能
 * 继续写库，撞上已经关闭的 Hikari 连接池，出现
 * {@code HikariDataSource ... has been closed} 并丢数据。</p>
 *
 * <p>解决办法：所有 Spider 统一通过 {@link #run(Spider)} 启动，本类记录活跃实例；
 * 应用关闭时调用 {@link #stopAll()} 让它们停下来。</p>
 *
 * <p>两种运行形态：</p>
 * <ul>
 *   <li>独立运行 crawler：{@code CrawlerService.main} 调用 {@link #installShutdownHook()}；</li>
 *   <li>被 admin 引入：{@code CrawlerBridgeConfig.destroy()} 调用 {@link #stopAll()}。</li>
 * </ul>
 */
public final class SpiderRegistry {

    private static final Set<Spider> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean HOOK_INSTALLED = new AtomicBoolean(false);

    private SpiderRegistry() {
    }

    /** 启动一个 Spider，并在结束后自动从活跃集合里移除。 */
    public static void run(Spider spider) {
        ACTIVE.add(spider);
        try {
            spider.run();
        } finally {
            ACTIVE.remove(spider);
        }
    }

    /** 停止所有正在运行的 Spider（应用关闭 / DevTools 重启时调用）。 */
    public static void stopAll() {
        if (ACTIVE.isEmpty()) {
            return;
        }
        System.out.println(">>> [SpiderRegistry] 正在停止 " + ACTIVE.size() + " 个 Spider");
        for (Spider spider : ACTIVE) {
            try {
                spider.stop();
            } catch (Exception e) {
                System.err.println(">>> [SpiderRegistry] 停止 Spider 失败: " + e.getMessage());
            }
        }
        ACTIVE.clear();
    }

    /** 独立运行时注册 JVM 关闭钩子；重复调用只会注册一次。 */
    public static void installShutdownHook() {
        if (HOOK_INSTALLED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(SpiderRegistry::stopAll, "crawler-spider-stop"));
        }
    }

    /** 当前活跃 Spider 数量，便于排查。 */
    public static int activeCount() {
        return ACTIVE.size();
    }
}
