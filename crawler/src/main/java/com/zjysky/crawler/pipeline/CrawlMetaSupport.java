package com.zjysky.crawler.pipeline;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zjysky.crawler.CrawlerConfig;
import com.zjysky.crawler.PriceSupport;
import com.zjysky.crawler.SpotSource;
import com.zjysky.common.database.mapper.MdCrawlFieldMapper;
import com.zjysky.common.database.mapper.MdCrawlLogMapper;
import com.zjysky.common.database.entity.MdCrawlField;
import com.zjysky.common.database.entity.MdCrawlLog;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 采集元信息工具：从每条行情的 raw_json 统计「字段字典」，落到 md_crawl_field。
 *
 * <p>四个 Processor 已经把源站<b>完整原始行</b>放进 raw_json，所以这里不用改
 * Processor、也不用反射 Model，直接反序列化 raw_json 就能统计到源站真正
 * 返回的每一个 key（包括映射到表列的和没映射的展示字段）。</p>
 *
 * <p>设计取舍：md_crawl_field / md_crawl_log 是辅助表，<b>写失败绝不能影响行情落库</b>，
 * 因此所有写入都包在 try-catch 里，异常只打提示、不抛出；
 * 关闭中的数据源（应用重启）直接跳过。</p>
 */
public final class CrawlMetaSupport {

    private static final String CFG = "crawler.meta.";
    private static final int MAX_SAMPLE = 200;
    private static final int MAX_MESSAGE = 500;

    private CrawlMetaSupport() {
    }

    /** 配置开关，默认开启；可用 -Dcrawler.meta.enabled=false 关闭。 */
    public static boolean enabled() {
        return CrawlerConfig.boolVal(CFG + "enabled", true);
    }

    /**
     * 一次采集成功后调用：统计字段字典并写入 md_crawl_field。
     *
     * @param factory       SqlSessionFactory
     * @param source        数据源编码 SHFE / SGE / CJ / SMM
     * @param taskType      任务类型 QUOTE / INTRADAY / NIGHT
     * @param rawJsonList   每条行情的原始 JSON 字符串（取自各 Model 的 rawJson）
     */
    public static void saveFields(SqlSessionFactory factory, String source, String taskType,
                                  List<String> rawJsonList) {
        if (!enabled() || rawJsonList == null || rawJsonList.isEmpty()) {
            return;
        }
        try {
            List<MdCrawlField> fields = buildFields(source, taskType, rawJsonList);
            if (fields.isEmpty()) {
                return;
            }
            try (SqlSession session = factory.openSession(true)) {
                int affected = session.getMapper(MdCrawlFieldMapper.class).upsertFields(fields);
                System.out.println(">>> [Meta] 字段字典 " + source + "/" + taskType
                        + " 写入/更新 " + fields.size() + " 个字段（影响行数 " + affected + "）");
            }
        } catch (Exception e) {
            if (PriceSupport.isClosedDataSource(e)) {
                System.err.println(">>> [Meta] 数据源已关闭，跳过字段字典写入");
                return;
            }
            System.err.println(">>> [Meta] 字段字典写入失败（不影响行情）: " + e.getMessage());
        }
    }

    /**
     * 纯统计、不写库。方便命令行预览某个源会采到哪些字段。
     *
     * <p>遍历每条 raw_json 的每个 key，统计非空数与首个非空样例值；
     * 中文标准名与列名由下方 FIELD_SCHEMA 映射表翻译，未登记的 key 原样保留、
     * column_name 置 null。</p>
     */
    public static List<MdCrawlField> buildFields(String source, String taskType,
                                                 List<String> rawJsonList) {
        // field_name -> [非空计数, 出现的总行数]
        Map<String, int[]> counter = new LinkedHashMap<>();
        Map<String, String> sample = new LinkedHashMap<>();
        Map<String, String> column = new LinkedHashMap<>();
        int rows = 0;

        for (String raw : rawJsonList) {
            JSONObject obj = parse(raw);
            if (obj == null) {
                continue;
            }
            rows++;
            for (String key : obj.keySet()) {
                Meta meta = meta(source, key);
                String fieldName = meta == null ? key : meta.label();
                String colName = meta == null ? null : meta.column();
                column.putIfAbsent(fieldName, colName);

                int[] c = counter.computeIfAbsent(fieldName, k -> new int[2]);
                c[1]++;
                String value = text(obj.get(key));
                if (value != null) {
                    c[0]++;
                    sample.putIfAbsent(fieldName, value);
                }
            }
        }
        if (rows == 0) {
            return List.of();
        }

        List<MdCrawlField> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : counter.entrySet()) {
            MdCrawlField f = new MdCrawlField();
            f.setSource(source);
            f.setTaskType(taskType);
            f.setFieldName(e.getKey());
            f.setColumnName(column.get(e.getKey()));
            // 整数除法会截断：302 行里只有 1 行有值时 1*100/302=0，
            // 但"0% 却有样例值"会误导，故只要非空数 > 0 就至少记 1%
            int nonEmpty = e.getValue()[0];
            f.setNonEmptyRate(nonEmpty == 0 ? 0 : Math.max(1, nonEmpty * 100 / rows));
            String sv = sample.get(e.getKey());
            f.setSampleValue(sv == null ? null
                    : (sv.length() > MAX_SAMPLE ? sv.substring(0, MAX_SAMPLE) : sv));
            out.add(f);
        }
        return out;
    }

    /**
     * 一次采集（成功或失败）后调用：追加一条 md_crawl_log。
     *
     * <p>WebMagic 单次 process 处理一页 = 一个请求，故 requests / pages 均为 1；
     * 失败时 records 传 0。start_time 由 end_time 减 cost_ms 反推。</p>
     */
    public static void saveLog(SqlSessionFactory factory, String source, String taskType,
                               boolean success, int records, long costMs, String message) {
        if (!enabled()) {
            return;
        }
        try {
            MdCrawlLog log = new MdCrawlLog();
            log.setSource(source);
            log.setTaskType(taskType);
            log.setStatus(success ? 1 : 0);
            log.setRecords(records);
            log.setRequests(1);
            log.setPages(1);
            log.setCostMs((int) Math.min(costMs, Integer.MAX_VALUE));
            log.setMessage(message == null ? null
                    : (message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message));
            log.setEndTime(LocalDateTime.now());
            long safeCost = Math.max(0, Math.min(costMs, 3_600_000L)); // 最多回推 1 小时，防溢出
            log.setStartTime(log.getEndTime().minusNanos(safeCost * 1_000_000L));

            try (SqlSession session = factory.openSession(true)) {
                int affected = session.getMapper(MdCrawlLogMapper.class).insertLog(log);
                System.out.println(">>> [Meta] 采集日志 " + source + "/" + taskType
                        + " status=" + (success ? 1 : 0) + " records=" + records
                        + " cost=" + costMs + "ms（影响行数 " + affected + "）");
            }
        } catch (Exception e) {
            if (PriceSupport.isClosedDataSource(e)) {
                System.err.println(">>> [Meta] 数据源已关闭，跳过采集日志写入");
                return;
            }
            System.err.println(">>> [Meta] 采集日志写入失败（不影响行情）: " + e.getMessage());
        }
    }

    /** 采集摘要文案，和 digintel 历史日志格式一致。 */
    public static String message(int records, long costMs) {
        return "处理 1 页，写入/更新 " + records + " 条，耗时 "
                + String.format(Locale.ROOT, "%.1f", costMs / 1000.0) + "s";
    }

    private static JSONObject parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** 源站脏值（"-"、"--"、空白）统一视作空，和 PriceSupport.decimal 的口径一致。 */
    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty() || "-".equals(s) || "--".equals(s) || "—".equals(s)) {
            return null;
        }
        return s;
    }

    /** 供命令行预览用：把字段字典按「非空率降序」打印。 */
    public static void printFields(String source, String taskType, List<String> rawJsonList) {
        List<MdCrawlField> fields = buildFields(source, taskType, rawJsonList);
        System.out.println("=== " + source + "/" + taskType + " 字段字典（共 "
                + fields.size() + " 个）===");
        fields.stream()
                .sorted((a, b) -> Integer.compare(
                        b.getNonEmptyRate() == null ? 0 : b.getNonEmptyRate(),
                        a.getNonEmptyRate() == null ? 0 : a.getNonEmptyRate()))
                .forEach(f -> System.out.printf(Locale.ROOT,
                        "  %-18s -> %-24s 非空率=%3d%%  样例=%s%n",
                        f.getFieldName(), String.valueOf(f.getColumnName()),
                        f.getNonEmptyRate() == null ? 0 : f.getNonEmptyRate(),
                        f.getSampleValue()));
    }

    // ================================================================
    // 源站原始字段名 → 字段字典元信息（原 CrawlFieldSchema，已并入本类）
    // ================================================================

    /** 一个原始字段的元信息：label → field_name，column → column_name（null 表示未结构化）。 */
    public record Meta(String label, String column) {
    }

    private static final Map<String, Map<String, Meta>> FIELD_SCHEMA = new LinkedHashMap<>();

    static {
        FIELD_SCHEMA.put("SHFE", shfeSchema());
        FIELD_SCHEMA.put("SGE", sgeSchema());
        // CJ / SMM 同属 smm.cn 平台，原始字段名一致，共用一份映射
        for (SpotSource spot : SpotSource.all()) {
            FIELD_SCHEMA.put(spot.name(), spotSchema());
        }
    }

    /** 查映射；source 大小写不敏感，未登记返回 null。 */
    private static Meta meta(String source, String rawKey) {
        if (source == null || rawKey == null) {
            return null;
        }
        Map<String, Meta> map = FIELD_SCHEMA.get(source.trim().toUpperCase(Locale.ROOT));
        return map == null ? null : map.get(rawKey);
    }

    private static Meta m(String label, String column) {
        return new Meta(label, column);
    }

    /** SHFE：历史大写 key + 当日延时的 lowercase key，映射到同一套列名。 */
    private static Map<String, Meta> shfeSchema() {
        Map<String, Meta> map = new LinkedHashMap<>();
        // —— 历史日行情 kx*.dat（大写 key）——
        map.put("PRODUCTNAME", m("品种", "product_name"));
        map.put("PRODUCTID", m("品种代码", "product_id"));
        map.put("PRODUCTGROUPID", m("品种组", "product_group"));
        map.put("PRODUCTCLASS", m("品种类别", "product_class"));
        map.put("DELIVERYMONTH", m("交割月", "contract_month"));
        map.put("OPENPRICE", m("开盘价", "open_price"));
        map.put("HIGHESTPRICE", m("最高价", "high_price"));
        map.put("LOWESTPRICE", m("最低价", "low_price"));
        map.put("CLOSEPRICE", m("收盘价", "close_price"));
        map.put("SETTLEMENTPRICE", m("结算价", "settle_price"));
        map.put("PRESETTLEMENTPRICE", m("昨结算", "pre_settle_price"));
        map.put("AVGPRICE", m("均价", "avg_price"));
        map.put("ZD1_CHG", m("涨跌1", "change_value"));
        map.put("ZD2_CHG", m("涨跌2", "change_value2"));
        map.put("VOLUME", m("成交量", "volume"));
        map.put("TASVOLUME", m("TAS成交量", "tas_volume"));
        map.put("OPENINTEREST", m("持仓量", "open_interest"));
        map.put("OPENINTERESTCHG", m("持仓变化", "open_interest_change"));
        map.put("TURNOVER", m("成交额", "turnover"));
        map.put("YEARVOLUME", m("年成交量", "year_volume"));
        map.put("YEARTURNOVER", m("年成交额", "year_turnover"));
        map.put("LASTPRICE", m("最新价", "last_price"));
        map.put("PRECLOSEPRICE", m("昨收", "pre_close_price"));
        map.put("UPDOWN", m("涨跌", "change_value"));
        map.put("UPDOWN2", m("涨跌2", "change_value2"));
        map.put("TRADINGDAY", m("交易日期", "trade_date"));
        map.put("REPORT_DATE", m("交易日期", "trade_date"));
        map.put("UPDATE_DATE", m("更新时间", "source_update_time"));
        // —— 当日延时行情 delaymarket_all.dat（小写 key）——
        map.put("contractname", m("合约", "symbol"));
        map.put("instrumentid", m("品种代码", "product_id"));
        map.put("updatetime", m("更新时间", "source_update_time"));
        map.put("openprice", m("开盘价", "open_price"));
        map.put("highprice", m("最高价", "high_price"));
        map.put("lowerprice", m("最低价", "low_price"));
        map.put("closeprice", m("收盘价", "close_price"));
        map.put("lastprice", m("最新价", "last_price"));
        map.put("precloseprice", m("昨收", "pre_close_price"));
        map.put("presettlementprice", m("昨结算", "pre_settle_price"));
        map.put("upperdown", m("涨跌", "change_value"));
        map.put("volume", m("成交量", "volume"));
        map.put("tasvolume", m("TAS成交量", "tas_volume"));
        map.put("lastv", m("最新成交量", "last_volume"));
        map.put("openinterest", m("持仓量", "open_interest"));
        map.put("turnover", m("成交额", "turnover"));
        map.put("bidprice", m("买价", "bid_price"));
        map.put("askprice", m("卖价", "ask_price"));
        return map;
    }

    /** SGE：Excel 中文表头本身就是 field_name，这里只补上目标列名。 */
    private static Map<String, Meta> sgeSchema() {
        Map<String, Meta> map = new LinkedHashMap<>();
        map.put("序号", m("序号", "seq_no"));
        map.put("日期", m("日期", "trade_date"));
        map.put("合约", m("合约", "contract"));
        map.put("开盘价", m("开盘价", "open_price"));
        map.put("最高价", m("最高价", "high_price"));
        map.put("最低价", m("最低价", "low_price"));
        map.put("收盘价", m("收盘价", "close_price"));
        map.put("结算价", m("结算价", "settle_price"));
        map.put("加权平均价", m("加权平均价", "avg_price"));
        map.put("涨跌（元）", m("涨跌（元）", "change_value"));
        map.put("涨跌", m("涨跌（元）", "change_value"));
        map.put("涨跌幅", m("涨跌幅", "change_pct"));
        map.put("成交量（kg）", m("成交量（kg）", "volume"));
        map.put("成交量", m("成交量（kg）", "volume"));
        map.put("成交金额（元）", m("成交金额（元）", "turnover"));
        map.put("市场持仓（手）", m("市场持仓（手）", "open_interest"));
        map.put("持仓量", m("市场持仓（手）", "open_interest"));
        map.put("交收方向", m("交收方向", "delivery_direction"));
        map.put("交收量（手）", m("交收量（手）", "delivery_volume"));
        // —— 当日实时延时行情页 /sjzx/yshqbg（服务端渲染 HTML 表格）——
        // 表没有 last_price 列，所以「最新价」按需求映射到 close_price，
        // 收盘后被 Excel 里的真正「收盘价」覆盖。
        map.put("最新价", m("最新价(延时)", "close_price"));
        map.put("今开盘", m("今开盘", "open_price"));
        return map;
    }

    /** CJ / SMM：现货接口字段相同；展示字段（*_show）没有独立列，column 留 null。 */
    private static Map<String, Meta> spotSchema() {
        Map<String, Meta> map = new LinkedHashMap<>();
        map.put("product_id", m("品种代码", "product_id"));
        map.put("productId", m("品种代码", "product_id"));
        map.put("品种代码", m("品种代码", "product_id"));
        map.put("renew_date", m("报价日期", "quote_date"));
        map.put("date", m("报价日期", "quote_date"));
        map.put("报价日期", m("报价日期", "quote_date"));
        map.put("average", m("均价", "avg_price"));
        map.put("均价", m("均价", "avg_price"));
        map.put("average_show", m("均价(展示)", null));
        map.put("highs", m("最高价", "high_price"));
        map.put("最高价", m("最高价", "high_price"));
        map.put("high_show", m("最高价(展示)", null));
        map.put("low", m("最低价", "low_price"));
        map.put("最低价", m("最低价", "low_price"));
        map.put("low_show", m("最低价(展示)", null));
        map.put("vchange", m("涨跌额", "change_value"));
        map.put("涨跌额", m("涨跌额", "change_value"));
        map.put("change_value_show", m("涨跌额(展示)", null));
        map.put("vchange_rate", m("涨跌幅", "change_pct"));
        map.put("涨跌幅", m("涨跌幅", "change_pct"));
        map.put("change_rate_show", m("涨跌幅(展示)", null));
        map.put("point_precision", m("价格精度", "point_precision"));
        map.put("价格精度", m("价格精度", "point_precision"));
        return map;
    }
}
