package com.zjysky.crawler.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * crawler 统一配置读取器。
 *
 * <p>crawler 是纯 Java SE 模块（没有 Spring Boot / Spring 容器），不能使用
 * {@code @Value} / {@code @ConfigurationProperties}，因此这里用 SnakeYAML 直接加载
 * {@code classpath:application.yml}，把层级结构扁平化成 {@code a.b.c} 形式的 key。</p>
 *
 * <p>取值优先级：JVM 系统属性 {@code -Da.b.c=...} &gt; 环境变量 {@code A_B_C}
 * &gt; application.yml。这样同一份配置既能提交到仓库，又能在部署时用环境变量覆盖密码等敏感项。</p>
 */
public final class CrawlerConfig {

    /** 唯一配置文件，位于 crawler/src/main/resources 下 */
    public static final String FILE = "application.yml";

    private static final Map<String, Object> FLAT = load();

    private CrawlerConfig() {
    }

    private static Map<String, Object> load() {
        Map<String, Object> flat = new LinkedHashMap<>();
        try (InputStream in = CrawlerConfig.class.getClassLoader().getResourceAsStream(FILE)) {
            if (in == null) {
                throw new IllegalStateException("classpath 下找不到 " + FILE
                        + "，请确认文件位于 crawler/src/main/resources/");
            }
            Map<String, Object> root = new Yaml().load(in);
            if (root != null) {
                flatten("", root, flat);
            }
        } catch (Exception e) {
            throw new IllegalStateException("加载 " + FILE + " 失败：" + e.getMessage(), e);
        }
        return flat;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<?, ?> node, Map<String, Object> out) {
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String key = prefix.isEmpty()
                    ? String.valueOf(entry.getKey())
                    : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?>) {
                flatten(key, (Map<?, ?>) value, out);
            } else {
                out.put(key, value);
            }
        }
    }

    /** 按优先级取原始值；未配置返回 null */
    private static Object resolve(String key) {
        String sys = System.getProperty(key);
        if (sys != null) {
            return sys;
        }
        String envKey = key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        String env = System.getenv(envKey);
        if (env != null) {
            return env;
        }
        return FLAT.get(key);
    }

    public static String str(String key, String defaultValue) {
        Object v = resolve(key);
        return v == null ? defaultValue : String.valueOf(v);
    }

    public static int intVal(String key, int defaultValue) {
        Object v = resolve(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("配置项 " + key + " 不是整数: " + v, e);
        }
    }

    public static long longVal(String key, long defaultValue) {
        Object v = resolve(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("配置项 " + key + " 不是长整数: " + v, e);
        }
    }

    public static boolean boolVal(String key, boolean defaultValue) {
        Object v = resolve(key);
        return v == null ? defaultValue : Boolean.parseBoolean(String.valueOf(v).trim());
    }

    /** 打印生效配置（密码自动打码），用于 `CrawlerService config` 自检 */
    public static void dump() {
        System.out.println(">>> [Config] 配置文件: classpath:" + FILE + "（共 " + FLAT.size() + " 项）");
        FLAT.forEach((k, v) -> {
            boolean secret = k.toLowerCase(Locale.ROOT).contains("password");
            String shown = secret ? "******" : String.valueOf(v);
            Object effective = resolve(k);
            if (effective != null && !String.valueOf(effective).equals(String.valueOf(v))) {
                shown = shown + "   <= 已被覆盖为: " + (secret ? "******" : effective);
            }
            System.out.println("  " + k + " = " + shown);
        });
    }
}
