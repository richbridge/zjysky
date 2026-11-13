package com.zjysky.config;

import com.zjysky.crawler.CrawlerConfig;
import com.zjysky.crawler.DbConfig;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前后端分离后，admin 与 crawler 的运行桥梁（只在 admin 模块内存在）。
 *
 * <p>crawler 是纯 Java SE 模块，自己读 {@code classpath:application.yml}、自己建连接池；
 * admin 是 Spring Boot 应用。这里在容器启动完成、第一个 HTTP 请求到达之前做两件事：</p>
 * <ol>
 *   <li>把 Spring 管理的 {@link DataSource} 注入 {@link DbConfig}：采集与接口查询共用同一个连接池，
 *       生命周期由 Spring 负责（避免两套池、DevTools 重启后连接池被关闭）；</li>
 *   <li>把 admin 的 {@code crawler.*} 配置透传给 {@link CrawlerConfig}，使收盘时间、节假日、
 *       各源保存目录等与后台页面看到的口径一致（crawler jar 内 yml 的配置仍然作为缺省值）。</li>
 * </ol>
 *
 * <p>这里<b>不启动</b> crawler 自带的定时线程：采集完全由前端页面 / REST 接口按需触发。</p>
 */
@Configuration
public class CrawlerBridgeConfig {

    /** admin 的 yaml 用 crawler.&lt;源&gt;.save-dir，crawler 内部读的是 .downloader.save-dir。 */
    private static final List<String> SPOT_SOURCES = List.of("shfe", "sge", "cj", "smm");

    @Bean
    public ApplicationRunner crawlerBridge(DataSource dataSource, ConfigurableEnvironment environment) {
        return args -> {
            DbConfig.useDataSource(dataSource);

            Map<String, Object> overlay = new LinkedHashMap<>();
            for (PropertySource<?> source : environment.getPropertySources()) {
                if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                    continue;
                }
                for (String key : enumerable.getPropertyNames()) {
                    // 只透传 crawler.*；putIfAbsent 保证高优先级 propertySource 先写入
                    if (key.startsWith("crawler.")) {
                        Object value = enumerable.getProperty(key);
                        if (value != null) {
                            overlay.putIfAbsent(key, value);
                        }
                    }
                }
            }
            // 兼容 admin 旧配置里的 save-dir 简写：映射到 crawler 实际读取的 key
            for (String src : SPOT_SOURCES) {
                String from = "crawler." + src + ".save-dir";
                String to = "crawler." + src + ".downloader.save-dir";
                Object value = overlay.get(from);
                if (value != null) {
                    overlay.putIfAbsent(to, value);
                }
            }
            // sge.realtime.downloader.save-dir 在两侧本来就同名，无需映射
            CrawlerConfig.overlay(overlay);

            System.out.println(">>> [CrawlerBridge] 已接入 Spring DataSource，透传 crawler.* 配置 "
                    + overlay.size() + " 项；采集由 /api/crawl/run 按需触发");
        };
    }
}
