package com.zjysky.crawler.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zjysky.crawler.mapper.ShfeMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * MyBatis-Plus 装配（crawler 是纯 Java SE 模块，非 Spring Boot，故手工构建 SqlSessionFactory）。
 *
 * <p>所有连接参数均来自 {@code classpath:application.yml}（由 {@link CrawlerConfig} 读取）：</p>
 * <ol>
 *   <li>{@code spring.datasource.*} → 建库自检 + HikariCP 连接池</li>
 *   <li>{@code crawler.db.mapper-location} → 注册 Mapper 接口（MP 注入 BaseMapper CRUD）</li>
 *   <li>加载 resources/mapper 下的 XML 中的自定义 SQL（建表 + 批量 upsert）</li>
 * </ol>
 */
public final class DbConfig {

    private static final String DS = "spring.datasource.";

    private static volatile SqlSessionFactory factory;
    private static volatile HikariDataSource dataSource;

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

    public static HikariDataSource getDataSource() {
        getSqlSessionFactory();
        return dataSource;
    }

    private static SqlSessionFactory build() {
        String url = CrawlerConfig.str(DS + "url", "jdbc:mysql://localhost:3306/zjysky");
        String user = CrawlerConfig.str(DS + "username", "richfan");
        String pwd = CrawlerConfig.str(DS + "password", "");
        String driver = CrawlerConfig.str(DS + "driver-class-name", "com.mysql.cj.jdbc.Driver");
        String mapperXml = CrawlerConfig.str("crawler.db.mapper-location", "mapper/ShfeMapper.xml");

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
        dataSource = new HikariDataSource(hc);
        Runtime.getRuntime().addShutdownHook(new Thread(DbConfig::close));

        try (Connection c = dataSource.getConnection()) {
            System.out.println(">>> [DB] 已连接: " + c.getCatalog()
                    + "（配置来自 " + CrawlerConfig.FILE + "）");
        } catch (SQLException e) {
            throw new IllegalStateException("连接数据库失败，请确认 " + CrawlerConfig.FILE
                    + " 中的 url/账号正确且库已创建：" + e.getMessage(), e);
        }

        String envId = CrawlerConfig.str("spring.application.name", "crawler");
        Environment env = new Environment(envId, new JdbcTransactionFactory(), dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration(env);

        GlobalConfig gc = GlobalConfigUtils.defaults();
        gc.setBanner(false);
        GlobalConfigUtils.setGlobalConfig(configuration, gc);
        configuration.setMapUnderscoreToCamelCase(true);

        configuration.addMapper(ShfeMapper.class);
        loadMapperXml(configuration, mapperXml);

        SqlSessionFactory f = new MybatisSqlSessionFactoryBuilder().build(configuration);
        System.out.println(">>> [DB] MyBatis-Plus 就绪，mapper=" + mapperXml);
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

    public static void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
