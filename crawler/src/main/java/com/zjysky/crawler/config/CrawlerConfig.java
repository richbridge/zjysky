package com.zjysky.crawler.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * crawler 统一配置读取器。
 *
 * <p>crawler 是纯 Java SE 模块（没有 Spring Boot / Spring 容器），不能使用
 * {@code @Value} / {@code @ConfigurationProperties}，因此这里用 SnakeYAML 直接加载
 * {@code classpath:application.yml}，把层级结构扁平化成 {@code a.b.c} 形式的 key。</p>
 *
 * <p>取值优先级：{@link #overlay(Map)} 注入项（admin / Spring Environment）
 * &gt; JVM 系统属性 {@code -Da.b.c=...} &gt; 环境变量 {@code A_B_C} &gt; application.yml。</p>
 */
public final class CrawlerConfig {

    /** 唯一配置文件，位于 crawler/src/main/resources 下 */
    public static final String FILE = "application.yml";

    /** 外部容器（admin 模块）注入的配置覆盖项，优先级最高 */
    private static final Map<String, Object> OVERRIDES = new ConcurrentHashMap<>();

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

    /**
     * 由 Spring Boot 容器在启动阶段注入配置（如 admin 的 application.yaml）。
     * 必须在任何组件首次读取配置之前调用；key 使用与 yml 一致的扁平形式 {@code a.b.c}。
     */
    public static void overlay(Map<String, ?> values) {
        values.forEach((k, v) -> {
            if (k != null && v != null) {
                OVERRIDES.put(k, v);
            }
        });
    }

    /** 注入单个配置项 */
    public static void put(String key, Object value) {
        if (value == null) {
            OVERRIDES.remove(key);
        } else {
            OVERRIDES.put(key, value);
        }
    }

    /** 当前生效的覆盖项（不含密码，用于自检） */
    public static Map<String, Object> overrides() {
        return Map.copyOf(OVERRIDES);
    }

    /** 按优先级取原始值；未配置返回 null */
    private static Object resolve(String key) {
        Object injected = OVERRIDES.get(key);
        if (injected != null) {
            return injected;
        }
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

    /**
     * 读取逗号分隔的字符串（或 yml 原生 List）为列表。
     *
     * <p>用于 SMM 专区 slug、多 Mapper XML 等配置；空项会自动去掉。</p>
     */
    public static List<String> list(String key, List<String> defaultValue) {
        Object v = resolve(key);
        if (v == null) {
            return defaultValue;
        }
        List<String> raw;
        if (v instanceof List<?> items) {
            raw = new ArrayList<>();
            for (Object item : items) {
                raw.add(String.valueOf(item));
            }
        } else {
            raw = Arrays.asList(String.valueOf(v).split(","));
        }
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            String trimmed = s == null ? "" : s.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** 打印生效配置（密码自动打码），用于 `CrawlerService config` 自检 */
    public static void dump() {
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
