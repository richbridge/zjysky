package com.zjysky.crawler.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zjysky.crawler.mapper.CrawlMetaMapper;
import com.zjysky.crawler.mapper.PriceMapper;
import com.zjysky.crawler.mapper.ShfeMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * MyBatis-Plus 装配（crawler 是纯 Java SE 模块，非 Spring Boot，故手工构建 SqlSessionFactory）。
 *
 * <p>两种运行形态：</p>
 * <ol>
 *   <li><b>独立运行</b>（{@code CrawlerService.main}）：按 {@code application.yml} 自建 HikariCP，
 *       并注册 JVM shutdown hook 负责关闭；</li>
 *   <li><b>被 admin 引入</b>：启动时先调用 {@link #useDataSource(DataSource)} 注入
 *       Spring 容器管理的 DataSource，本类不再建池、不再注册 hook，池的生命周期完全交给
 *       Spring（避免 DevTools 热重启后出现 "HikariDataSource has been closed" 和连接泄漏）。</li>
 * </ol>
 *
 * <p>所有连接参数均来自 {@code classpath:application.yml}（由 {@link CrawlerConfig} 读取），
 * admin 环境下可由 {@link CrawlerConfig#overlay(java.util.Map)} 覆盖。</p>
 */
public final class DbConfig {

    private static final String DS = "spring.datasource.";

    private static volatile SqlSessionFactory factory;
    /** 独立运行时自建的连接池 */
    private static volatile HikariDataSource ownedDataSource;
    /** admin 注入的、由 Spring 容器管理生命周期的连接池 */
    private static volatile DataSource externalDataSource;

    private DbConfig() {
    }

    public static SqlSessionFactory getSqlSessionFactory() {
        if (factory == null) {
            synchronized (DbConfig.class) {
                if (factory == null) {
                    factory = build();
                }
            }
        }
        return factory;
    }

    /** 当前生效的数据源（独立运行时为自建池，admin 中为 Spring 注入的池） */
    public static DataSource getDataSource() {
        getSqlSessionFactory();
        return externalDataSource != null ? externalDataSource : ownedDataSource;
    }

    /**
     * 注入外部容器的数据源，必须在首次 {@link #getSqlSessionFactory()} 之前调用。
     * 典型调用点：admin 模块的 {@code @Bean} 创建 DataSource 时。
     */
    public static void useDataSource(DataSource dataSource) {
        DataSource ds = Objects.requireNonNull(dataSource, "dataSource");
        synchronized (DbConfig.class) {
            if (factory != null) {
                if (externalDataSource == ds) {
                    return;
                }
                throw new IllegalStateException("SqlSessionFactory 已初始化，useDataSource 必须在首次采集前调用");
            }
            externalDataSource = ds;
        }
    }

    private static SqlSessionFactory build() {
        String url = CrawlerConfig.str(DS + "url", "jdbc:mysql://localhost:3306/zjysky");
        String user = CrawlerConfig.str(DS + "username", "richfan");
        String pwd = CrawlerConfig.str(DS + "password", "");
        String driver = CrawlerConfig.str(DS + "driver-class-name", "com.mysql.cj.jdbc.Driver");
        // 优先读 mapper-locations（逗号分隔）；保留 mapper-location 作为旧配置兼容
        List<String> mapperXmls = CrawlerConfig.list("crawler.db.mapper-locations",
                List.of(CrawlerConfig.str("crawler.db.mapper-location", "mapper/ShfeMapper.xml")));

        DataSource ds = externalDataSource;
        boolean managedBySpring = ds != null;
        if (!managedBySpring) {
            if (CrawlerConfig.boolVal("crawler.db.auto-create-database", true)) {
                ensureDatabase(url, user, pwd, driver);
            }
            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl(url);
            hc.setUsername(user);
            hc.setPassword(pwd);
            hc.setDriverClassName(driver);
            hc.setPoolName(CrawlerConfig.str(DS + "hikari.pool-name", "zjysky-crawler-pool"));
            hc.setMaximumPoolSize(CrawlerConfig.intVal(DS + "hikari.maximum-pool-size", 5));
            hc.setConnectionTimeout(CrawlerConfig.longVal(DS + "hikari.connection-timeout", 30000L));
            hc.setIdleTimeout(CrawlerConfig.longVal(DS + "hikari.idle-timeout", 600000L));
            HikariDataSource hikari = new HikariDataSource(hc);
            ownedDataSource = hikari;
            ds = hikari;
            Runtime.getRuntime().addShutdownHook(new Thread(DbConfig::close));
        }

        DataSource effective = ds;
        try (Connection c = effective.getConnection()) {
            System.out.println(">>> [DB] 已连接: " + c.getCatalog()
                    + "（" + (managedBySpring ? "Spring 容器注入的 DataSource" : "自建连接池")
                    + "，配置来自 " + CrawlerConfig.FILE + " / overlay）");
        } catch (SQLException e) {
            throw new IllegalStateException("连接数据库失败，请确认 " + CrawlerConfig.FILE
                    + " 中的 url/账号正确且库已创建：" + e.getMessage(), e);
        }

        String envId = CrawlerConfig.str("spring.application.name", "crawler");
        Environment env = new Environment(envId, new JdbcTransactionFactory(), effective);
        MybatisConfiguration configuration = new MybatisConfiguration(env);

        GlobalConfig gc = GlobalConfigUtils.defaults();
        gc.setBanner(false);
        GlobalConfigUtils.setGlobalConfig(configuration, gc);
        configuration.setMapUnderscoreToCamelCase(true);

        configuration.addMapper(ShfeMapper.class);
        configuration.addMapper(PriceMapper.class);
        configuration.addMapper(CrawlMetaMapper.class);
        for (String mapperXml : mapperXmls) {
            loadMapperXml(configuration, mapperXml);
        }

        SqlSessionFactory f = new MybatisSqlSessionFactoryBuilder().build(configuration);
        System.out.println(">>> [DB] MyBatis-Plus 就绪，mapper=" + mapperXmls);
        return f;
    }

    /** 库不存在时尽力创建；账号常无 CREATE DATABASE 权限，此时跳过，由后面的连库自检给出结论 */
    private static void ensureDatabase(String url, String user, String pwd, String driver) {
        String serverUrl = toServerUrl(url);
        String dbName = databaseName(url);
        if (dbName == null || dbName.isBlank()) {
            return;
        }
        try {
            Class.forName(driver);
            try (Connection c = DriverManager.getConnection(serverUrl, user, pwd);
                 Statement st = c.createStatement()) {
                st.execute("CREATE DATABASE IF NOT EXISTS `" + dbName
                        + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
                System.out.println(">>> [DB] 数据库就绪: " + dbName);
            }
        } catch (ClassNotFoundException | SQLException e) {
            // 库已存在或账号无 CREATE 权限时忽略即可
            System.out.println(">>> [DB] 跳过自动建库（库已存在或账号无 CREATE 权限）：" + e.getMessage());
        }
    }

    /** 把 jdbc:mysql://host:port/db?params 转成 jdbc:mysql://host:port/?params，用于建库自检 */
    public static String toServerUrl(String url) {
        int scheme = url.indexOf("//");
        if (scheme < 0) {
            return url;
        }
        int hostStart = scheme + 2;
        int path = url.indexOf('/', hostStart);
        if (path < 0) {
            return url + "/";
        }
        int q = url.indexOf('?', path);
        String params = q >= 0 ? url.substring(q) : "";
        return url.substring(0, hostStart) + url.substring(hostStart, path) + "/" + params;
    }

    /** 从 JDBC URL 提取库名 */
    public static String databaseName(String url) {
        int scheme = url.indexOf("//");
        if (scheme < 0) {
            return null;
        }
        int hostStart = scheme + 2;
        int path = url.indexOf('/', hostStart);
        if (path < 0) {
            return null;
        }
        int end = url.indexOf('?', path);
        String name = end >= 0 ? url.substring(path + 1, end) : url.substring(path + 1);
        return name.trim().isEmpty() ? null : name;
    }

    /** 手工加载放在 resources/mapper 下的 XML（无 Spring 时需显式调用 XMLMapperBuilder） */
    private static void loadMapperXml(MybatisConfiguration configuration, String mapperXml) {
        try (InputStream in = Resources.getResourceAsStream(mapperXml)) {
            if (in == null) {
                throw new IllegalStateException("classpath 下找不到 " + mapperXml
                        + "（检查 crawler.db.mapper-location）");
            }
            new XMLMapperBuilder(in, configuration, mapperXml,
                    configuration.getSqlFragments()).parse();
        } catch (Exception e) {
            throw new IllegalStateException("加载 " + mapperXml + " 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 释放本类持有的静态引用；只关闭「自建」连接池，
     * 外部注入的 DataSource 由 Spring 容器负责关闭。
     */
    public static void close() {
        synchronized (DbConfig.class) {
            HikariDataSource own = ownedDataSource;
            if (own != null && !own.isClosed()) {
                own.close();
            }
            ownedDataSource = null;
            externalDataSource = null;
            factory = null;
        }
    }
}
