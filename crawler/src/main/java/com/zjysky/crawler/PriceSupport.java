package com.zjysky.crawler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zjysky.common.database.entity.MdSourceProduct;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 四个数据源共用的小工具，放在根包。
 *
 * <p>只做「脏数据整理 + 表结构自检 + 关联合约提取 + 组件间传值 key」这类重复工作，
 * 不负责下载、解析、落库，也不启动爬虫。Processor / Pipeline / Spider 都可以直接调用。</p>
 *
 * <p>为什么要单独放：SGE 的 Excel 会给出 {@code 1.55%}、{@code 1.30714E7}、
 * {@code -}；CJ / SMM 的 JSON 会给出小数比例 {@code -0.00124} 和展示串
 * {@code -0.12%}。这些值如果直接塞进 DECIMAL 列会报错或写错量纲，
 * 所以统一在这里转成 BigDecimal / yyyy-MM-dd 字符串。</p>
 */
public final class PriceSupport {

    private static final Pattern NUMBER = Pattern.compile(
            "^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$");

    /** 表名只允许字母/数字/下划线，且必须以字母或下划线开头。 */
    private static final Pattern SAFE_TABLE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    // ================================================================
    // 0. WebMagic 各组件之间传值的 key 常量（原 CrawlKeys，已并入本类）
    // ================================================================

    /** ResultItems：品种发现得到的合约列表，List&lt;MdSourceProduct&gt;。 */
    public static final String SOURCE_PRODUCTS = "sourceProducts";

    /** ResultItems：历史响应里附带的 LME / SHFE 关联合约列表。 */
    public static final String LINKED_PRODUCTS = "linkedProducts";

    /** ResultItems：现货报价行列表（CJ / SMM 共用，List&lt;SpotPrice&gt;）。 */
    public static final String SPOT_ROWS = "spotList";

    /** Request / ResultItems：当前合约 id。 */
    public static final String PRODUCT_ID = "productId";
    /** Request / ResultItems：当前合约名称。 */
    public static final String PRODUCT_NAME = "productName";
    /** Request：品种发现时该页面所属的专区名。 */
    public static final String PRODUCT_GROUP = "productGroup";

    /** Request：行情区间的起止日期（yyyy-MM-dd）。 */
    public static final String START_DATE = "startDate";
    public static final String END_DATE = "endDate";

    private PriceSupport() {
    }

    // ================================================================
    // 1. 字符串 / 数字 / 日期清洗
    // ================================================================

    /** 任何对象转字符串，null 与空白都返回 null。 */
    public static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /** 从 JSON 对象里取第一个非空的 key，兼容源站改字段名。 */
    public static String pick(JSONObject item, String... keys) {
        if (item == null) {
            return null;
        }
        for (String key : keys) {
            String v = text(item.get(key));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * 转成 BigDecimal。支持：
     * <ul>
     *   <li>普通数字：{@code 934.02}</li>
     *   <li>科学计数法：{@code 1.30714E7}（SGE Excel 的成交金额）</li>
     *   <li>带千分位：{@code 1,234.56}</li>
     *   <li>脏值：{@code -}、{@code --}、{@code ""} → null</li>
     * </ul>
     */
    public static BigDecimal decimal(Object value) {
        String s = text(value);
        if (s == null) {
            return null;
        }
        s = s.replace(",", "").replace("%", "").replace(" ", "");
        if (s.isEmpty() || "-".equals(s) || "--".equals(s) || "—".equals(s)) {
            return null;
        }
        if (!NUMBER.matcher(s).matches()) {
            return null;
        }
        try {
            return new BigDecimal(s).stripTrailingZeros();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 转整数；失败返回 null。 */
    public static Integer intValue(Object value) {
        BigDecimal d = decimal(value);
        return d == null ? null : d.intValue();
    }

    /**
     * 归一成 yyyy-MM-dd 字符串（表的 DATE 列可以直接吃这个格式）。
     * 支持 {@code 2025-11-10}、{@code 2025/11/10}、{@code 20251110}、
     * {@code 2026-11-11 11:30:00}（取前 10 位）。
     */
    public static String date(Object value) {
        if (value instanceof LocalDate d) {
            return d.toString();
        }
        String s = text(value);
        if (s == null) {
            return null;
        }
        if (s.length() > 10) {
            s = s.substring(0, 10);
        }
        s = s.replace('/', '-');
        try {
            if (s.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
                String[] p = s.split("-");
                return LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                        Integer.parseInt(p[2])).toString();
            }
            if (s.matches("\\d{8}")) {
                return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE).toString();
            }
        } catch (Exception ignored) {
            // 非法日期按 null 处理，避免一整批数据因为单行脏日期全部落库失败
        }
        return null;
    }

    /**
     * 涨跌幅统一成「百分数数值」。
     *
     * <ul>
     *   <li>{@code "1.55%"} → {@code 1.55}（SGE Excel 口径）</li>
     *   <li>{@code -0.00124} → {@code -0.124}（CJ / SMM 的 vchange_rate 是小数比例）</li>
     *   <li>{@code "-0.12%"} → {@code -0.12}（*_show 展示字段兜底）</li>
     * </ul>
     */
    public static BigDecimal percent(Object value) {
        String s = text(value);
        if (s == null) {
            return null;
        }
        boolean hasPercent = s.endsWith("%");
        BigDecimal d = decimal(s);
        if (d == null) {
            return null;
        }
        return hasPercent ? d.stripTrailingZeros() : d.multiply(BigDecimal.valueOf(100)).stripTrailingZeros();
    }

    /**
     * 判断异常是否由「数据源已关闭」引起。
     *
     * <p>应用正在关闭 / DevTools 热重启时，Hikari 连接池会先被关掉。
     * 此时如果某个 Spider 线程还在写库，这次写入本就该丢弃；
     * 识别出来后只打一条提示，不把它当成业务失败刷一堆异常。</p>
     */
    public static boolean isClosedDataSource(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m == null) {
                continue;
            }
            String lm = m.toLowerCase(Locale.ROOT);
            if (lm.contains("has been closed")
                    || lm.contains("already closed")
                    || lm.contains("pool has been shutdown")
                    || lm.contains("connection is closed")
                    || lm.contains("connection closed")) {
                return true;
            }
        }
        return false;
    }

    /** 对象序列化成 raw_json 字符串。 */
    public static String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.toJSONString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    // ================================================================
    // 2. 表结构自检
    // ================================================================

    /**
     * 任意来源的日期字符串 → LocalDate（先归一化成 yyyy-MM-dd 再解析）。
     * 解析失败返回 null，与 {@link #date(Object)} 的容错口径一致。
     */
    public static LocalDate localDate(Object value) {
        String s = date(value);
        return s == null ? null : LocalDate.parse(s);
    }

    /**
     * 表名白名单校验。
     *
     * <p>MyBatis 的 {@code #{}} 不能用于表名，只能 {@code ${}} 拼接，
     * 所以凡是把表名交给 SQL 的地方都先过这一关，
     * 配置里写错表名会立刻报错，也不会拼出注入语句。</p>
     *
     * @return 去空格后的表名，便于链式使用
     */
    public static String requireSafeTable(String table) {
        if (table == null || !SAFE_TABLE.matcher(table.trim()).matches()) {
            throw new IllegalArgumentException("行情表名不合法，拒绝拼进 SQL: " + table);
        }
        return table.trim();
    }

    /**
     * 启动 Pipeline 前检查目标表的必要列是否齐全。
     *
     * <p>四张价格表是已存在的业务宽表，本模块<b>不建表、不删表、不改结构</b>。
     * 一旦源站或业务方改了列，这里会立刻抛错，而不是静默丢字段。</p>
     *
     * <p>列清单直接通过 JDBC {@link DatabaseMetaData} 读取，不经过任何 Mapper，
     * 这样 Mapper XML 严格与业务表一一对应，不掺杂 information_schema 查询。</p>
     */
    public static void checkColumns(SqlSessionFactory factory, String table, List<String> required) {
        if (!CrawlerConfig.boolVal("crawler.db.field-check", true)) {
            System.out.println(">>> [Schema] 已跳过字段自检（crawler.db.field-check=false）: " + table);
            return;
        }
        try (SqlSession session = factory.openSession(true)) {
            Connection conn = session.getConnection();
            DatabaseMetaData meta = conn.getMetaData();
            Set<String> actual = new TreeSet<>();
            try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table, null)) {
                while (rs.next()) {
                    actual.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
            if (actual.isEmpty()) {
                throw new IllegalStateException("表 " + table + " 不存在或读不到字段");
            }
            List<String> missing = new ArrayList<>();
            for (String c : required) {
                if (!actual.contains(c)) {
                    missing.add(c);
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException("表 " + table + " 字段不完整，缺少: " + missing);
            }
            Set<String> unused = new TreeSet<>(actual);
            unused.removeAll(required);
            System.out.println(">>> [Schema] 字段完整性检测通过: " + table
                    + " 共 " + actual.size() + " 列，本次写入所需 " + required.size()
                    + " 列齐全；未使用列（DB 默认值/其它任务维护）: " + unused);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("表 " + table + " 字段自检失败: " + e.getMessage(), e);
        }
    }

    // ================================================================
    // 3. CJ / SMM 历史行情通用处理
    // ================================================================

    /** 从历史接口根节点里找出数据数组，兼容 data / list / result / rows。 */
    public static JSONArray historyRows(JSONObject root) {
        if (root == null) {
            return new JSONArray();
        }
        for (String key : new String[]{"data", "list", "result", "rows"}) {
            JSONArray arr = root.getJSONArray(key);
            if (arr != null) {
                return arr;
            }
        }
        return new JSONArray();
    }

    /**
     * 按 quote-mode 筛选要落库的行。
     *
     * @param all       接口返回的全部行
     * @param mode      latest（默认）/ all / range
     * @param startDate 起始日期 yyyy-MM-dd，range 模式用
     * @param endDate   结束日期 yyyy-MM-dd，range 模式用
     */
    public static List<JSONObject> selectRows(JSONArray all, String mode,
                                              String startDate, String endDate) {
        List<JSONObject> rows = new ArrayList<>();
        if (all == null) {
            return rows;
        }
        for (int i = 0; i < all.size(); i++) {
            JSONObject row = all.getJSONObject(i);
            if (row != null) {
                rows.add(row);
            }
        }
        String m = mode == null || mode.isBlank() ? "latest" : mode.toLowerCase(Locale.ROOT);
        if ("all".equals(m)) {
            return rows;
        }
        if ("range".equals(m)) {
            String from = date(startDate);
            String to = date(endDate);
            List<JSONObject> kept = new ArrayList<>();
            for (JSONObject row : rows) {
                String d = pick(row, "renew_date", "报价日期");
                String normalized = date(d);
                if (normalized != null && from != null && to != null
                        && normalized.compareTo(from) >= 0 && normalized.compareTo(to) <= 0) {
                    kept.add(row);
                }
            }
            return kept;
        }
        // latest：只保留本次响应里报价日期最新的那批
        String newest = null;
        for (JSONObject row : rows) {
            String d = date(pick(row, "renew_date", "报价日期"));
            if (d != null && (newest == null || d.compareTo(newest) > 0)) {
                newest = d;
            }
        }
        if (newest == null) {
            return rows;
        }
        List<JSONObject> kept = new ArrayList<>();
        for (JSONObject row : rows) {
            if (newest.equals(date(pick(row, "renew_date", "报价日期")))) {
                kept.add(row);
            }
        }
        return kept;
    }

    /**
     * 从 CJ / SMM 历史响应里提取 LME / SHFE 关联合约 id。
     *
     * <p>CJ 顶层是 {@code lme_list} / {@code shfe_list}，SMM 顶层是
     * {@code lme_data} / {@code shfe_data}。它们不是主行情，但也是"合约 id 信息"。
     * 为了不和主合约的纯数字 id 撞唯一键，这里统一加前缀：
     * {@code LME:201710310001}、{@code SHFE:cu}。</p>
     */
    public static List<MdSourceProduct> linkedProducts(String source, JSONObject root) {
        Map<String, MdSourceProduct> out = new LinkedHashMap<>();
        if (root == null) {
            return new ArrayList<>();
        }
        collectLinked(source, root.getJSONArray("lme_list"), "LME:", "LME联动合约", out);
        collectLinked(source, root.getJSONArray("shfe_list"), "SHFE:", "SHFE联动合约", out);
        collectLinked(source, root.getJSONArray("lme_data"), "LME:", "LME联动合约", out);
        collectLinked(source, root.getJSONArray("shfe_data"), "SHFE:", "SHFE联动合约", out);
        return new ArrayList<>(out.values());
    }

    /**
     * 品种字典按 productId 去重，保留首次出现的顺序。
     *
     * <p>CJ / SMM 的合约会同时出现在多个专区页面里，同一个 product_id 可能被
     * 发现多次。逐合约采集前先去重，避免对同一 URL 重复发请求、重复落库。
     * 去重损失的只是「同一合约在其它专区里的重复记录」，行情数据不受影响。</p>
     */
    public static List<MdSourceProduct> dedupProducts(List<MdSourceProduct> products) {
        if (products == null || products.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, MdSourceProduct> unique = new LinkedHashMap<>();
        for (MdSourceProduct p : products) {
            if (p != null && p.getProductId() != null && !p.getProductId().isBlank()) {
                unique.putIfAbsent(p.getProductId(), p);
            }
        }
        if (unique.size() < products.size()) {
            System.out.println(">>> [Support] 合约字典去重: " + products.size()
                    + " → " + unique.size() + "（跨专区重复的合约只抓一次）");
        }
        return new ArrayList<>(unique.values());
    }

    private static void collectLinked(String source, JSONArray array, String prefix,
                                      String group, Map<String, MdSourceProduct> out) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject row = array.getJSONObject(i);
            if (row == null) {
                continue;
            }
            String rawId = pick(row, "product_id", "productId", "id");
            if (rawId == null) {
                continue;
            }
            String id = prefix + rawId;
            if (out.containsKey(id)) {
                continue;
            }
            String unit = pick(row, "unit", "单位");
            MdSourceProduct p = new MdSourceProduct();
            p.setSource(source);
            p.setProductId(id);
            p.setProductName(unit == null ? rawId : rawId + "（" + unit + "）");
            p.setProductGroup(group);
            p.setEnabled(1);
            out.put(id, p);
        }
    }
}
