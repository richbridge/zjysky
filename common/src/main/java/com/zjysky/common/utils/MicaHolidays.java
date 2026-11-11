package com.zjysky.common.utils;

import net.dreamlu.mica.holidays.core.HolidaysApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 节假日 + 各源收盘时间判断（Spring / admin 侧）。
 *
 * <p><b>它回答的问题</b>：今天是不是交易日？这个源收盘了没有？
 * 现在该去采「盘中实时」还是「价格已定稿的收盘历史」？</p>
 *
 * <h3>和 crawler/CloseTimeGuard 的关系</h3>
 * <p>crawler 是纯 Java SE 模块，不能依赖 Spring 和 mica-holidays，
 * 所以那边有一份 {@code com.zjysky.crawler.support.CloseTimeGuard}。
 * 两边<b>读同一套 {@code crawler.market-close.*} 配置，方法名和语义一一对应</b>：</p>
 * <pre>
 *   MicaHolidays（本类，admin）        CloseTimeGuard（crawler 独立运行）
 *   ------------------------------    ------------------------------
 *   isTradeDate / isWorkday           isTradeDate
 *   isAfterClose                      shouldCollectHistory
 *   shouldCollectHistory              shouldCollectHistory
 *   isIntraday                        isIntraday
 *   lastClosedTradeDate               lastClosedTradeDate
 *   status                            status
 * </pre>
 * <p>差别只在节假日数据来源：本类用 {@link HolidaysApi}（mica 官方 2019~2026
 * 法定节假日数据，含调休上班日），CloseTimeGuard 用配置里的 holidays /
 * extra-workdays 兜底。admin 侧判断更准，crawler 侧零依赖也能跑。</p>
 *
 * <h3>收盘默认口径（Asia/Shanghai，可用配置覆盖）</h3>
 * <pre>
 *   SHFE 15:00   SGE 15:30   CJ 15:00   SMM 17:00（晚上采集）
 * </pre>
 */
@Service
public class MicaHolidays {

    private static final String CFG = "crawler.market-close.";
    private static final List<String> SOURCES = List.of("SHFE", "SGE", "CJ", "SMM");
    private static final Map<String, LocalTime> DEFAULT_CLOSE = Map.of(
            "SHFE", LocalTime.of(15, 0),
            "SGE", LocalTime.of(15, 30),
            "CJ", LocalTime.of(15, 0),
            "SMM", LocalTime.of(17, 0));

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter YYYY_MM_DD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final HolidaysApi holidaysApi;
    private final Environment env;

    /** 保留单参构造，方便不跑 Spring 容器时手工 new 出来做单元测试。 */
    public MicaHolidays(HolidaysApi holidaysApi) {
        this(holidaysApi, null);
    }

    /** Spring 注入：Environment 用来读 crawler.market-close.* 收盘配置。 */
    @Autowired
    public MicaHolidays(HolidaysApi holidaysApi, Environment env) {
        this.holidaysApi = holidaysApi;
        this.env = env;
    }

    // ================================================================
    // 1. 原有的工作日工具（保留，Index 控制器仍在用）
    // ================================================================

    /** 是否工作日：mica 官方数据，法定节假日为 false，调休上班日为 true。 */
    public boolean isWorkday(LocalDate date) {
        return holidaysApi.isWeekdays(date);
    }

    /** 获取上一工作日的 LocalDate 对象。 */
    public LocalDate getPreviousWorkday(LocalDate date) {
        LocalDate previousDay = date.minusDays(1);
        for (int i = 0; i < 30; i++) {
            if (isWorkday(previousDay)) {
                return previousDay;
            }
            previousDay = previousDay.minusDays(1);
        }
        return null;
    }

    /** 上一工作日，yyyyMMdd 格式（例：20261109）。 */
    public String getPreviousWorkdayYyyyMMdd(LocalDate date) {
        LocalDate result = getPreviousWorkday(date);
        return result == null ? null : result.format(YYYYMMDD);
    }

    /** 上一工作日，yyyy-MM-dd 格式（例：2026-11-09）。 */
    public String getPreviousWorkdayYyyyMmDd(LocalDate date) {
        LocalDate result = getPreviousWorkday(date);
        return result == null ? null : result.format(YYYY_MM_DD);
    }

    // ================================================================
    // 2. 交易日 + 收盘时间判断（需求 3 的核心）
    // ================================================================

    /** 支持的四个源。 */
    public List<String> sources() {
        return SOURCES;
    }

    /** 是否启用收盘守卫；关掉后 shouldCollectHistory 一律放行（手动补历史用）。 */
    public boolean enabled() {
        return bool(CFG + "enabled", true);
    }

    /** 时区，默认 Asia/Shanghai。 */
    public ZoneId zone() {
        return ZoneId.of(prop(CFG + "zone", "Asia/Shanghai"));
    }

    /** 当前时刻（按配置时区）。 */
    public LocalDateTime now() {
        return LocalDateTime.now(zone());
    }

    /** 配置里显式扣除的法定节假日（yyyy-MM-dd，逗号分隔）。 */
    public List<String> holidays() {
        return list(CFG + "holidays");
    }

    /** 配置里显式加回的调休上班日（yyyy-MM-dd，逗号分隔）。 */
    public List<String> extraWorkdays() {
        return list(CFG + "extra-workdays");
    }

    /**
     * 是否交易日：先把配置里的「调休上班 / 额外节假日」覆盖上去，
     * 其余交给 mica 官方节假日库判断。
     */
    public boolean isTradeDate(LocalDate date) {
        if (date == null) {
            return false;
        }
        String iso = date.toString();
        if (extraWorkdays().contains(iso)) {
            return true;
        }
        if (holidays().contains(iso)) {
            return false;
        }
        return isWorkday(date);
    }

    /**
     * 某个源的收盘时间：优先读 {@code crawler.market-close.close-times.{SOURCE}}，
     * 例如 close-times.SHFE = 15:00。读不到就用内置默认值。
     */
    public LocalTime closeTime(String source) {
        String src = normalize(source);
        String raw = firstProperty(
                CFG + "close-times." + src,
                CFG + "close-times." + src.toLowerCase(Locale.ROOT),
                CFG + "close-times." + capitalize(src));
        if (raw != null) {
            try {
                return LocalTime.parse(raw);
            } catch (Exception ignored) {
                // 配置写错就退回默认值，不让整个 admin 启动失败
            }
        }
        return DEFAULT_CLOSE.getOrDefault(src, LocalTime.of(15, 0));
    }

    /**
     * 现在是否「已经过了这个源的收盘时间」（纯时间判断，不看 enabled 开关）。
     * 交易日且过了收盘时间，价格才算定稿。
     */
    public boolean isAfterClose(String source, LocalDateTime now) {
        LocalDateTime at = now == null ? now() : now;
        return isTradeDate(at.toLocalDate())
                && !at.toLocalTime().isBefore(closeTime(source));
    }

    public boolean isAfterClose(String source) {
        return isAfterClose(source, null);
    }

    /**
     * 现在是否该采收盘历史数据：守卫关闭时无条件放行（方便手动补历史），
     * 否则要求「是交易日 + 已过收盘时间」。
     */
    public boolean shouldCollectHistory(String source, LocalDateTime now) {
        if (!enabled()) {
            return true;
        }
        return isAfterClose(source, now);
    }

    public boolean shouldCollectHistory(String source) {
        return shouldCollectHistory(source, null);
    }

    /**
     * 是否处于盘中：交易日且还没到收盘时间。
     * 只有 SHFE / SGE 在这个窗口采当日实时；CJ / SMM 没有实时口径，等收盘即可。
     */
    public boolean isIntraday(String source, LocalDateTime now) {
        if (!enabled()) {
            return false;
        }
        LocalDateTime at = now == null ? now() : now;
        return isTradeDate(at.toLocalDate()) && at.toLocalTime().isBefore(closeTime(source));
    }

    public boolean isIntraday(String source) {
        return isIntraday(source, null);
    }

    /** 是否允许实时采集（需求 4：当日实时只采 SHFE / SGE）。 */
    public boolean supportsRealtime(String source) {
        String src = normalize(source);
        return "SHFE".equals(src) || "SGE".equals(src);
    }

    /**
     * 上一个「已收盘交易日」：今天交易日且已过收盘 → 今天；
     * 否则往前找最近的交易日（最多找 15 天兜底）。
     * 定时任务据此决定采哪一天的历史数据。
     */
    public LocalDate lastClosedTradeDate(String source, LocalDateTime now) {
        LocalDateTime at = now == null ? now() : now;
        LocalDate d = at.toLocalDate();
        if (isTradeDate(d) && !at.toLocalTime().isBefore(closeTime(source))) {
            return d;
        }
        d = d.minusDays(1);
        for (int i = 0; i < 15; i++) {
            if (isTradeDate(d)) {
                return d;
            }
            d = d.minusDays(1);
        }
        return at.toLocalDate();
    }

    public LocalDate lastClosedTradeDate(String source) {
        return lastClosedTradeDate(source, null);
    }

    /** 某源当前采集窗口的中文说明，给 HTTP 接口 / 日志直接展示。 */
    public String status(String source, LocalDateTime now) {
        LocalDateTime at = now == null ? now() : now;
        String src = normalize(source);
        if (!isTradeDate(at.toLocalDate())) {
            return src + " " + at.toLocalDate() + " 非交易日（周末/节假日），跳过收盘采集";
        }
        LocalTime close = closeTime(src);
        if (at.toLocalTime().isBefore(close)) {
            return src + " " + at.toLocalTime() + " < 收盘 " + close
                    + "，盘中：" + (supportsRealtime(src) ? "可采当日实时" : "现货报价未定稿")
                    + "，暂不采收盘历史";
        }
        return src + " " + at.toLocalTime() + " >= 收盘 " + close
                + "，已收盘：价格已定稿，可采收盘历史数据";
    }

    public String status(String source) {
        return status(source, null);
    }

    // ================================================================
    // 3. 覆盖策略（需求 5：收盘数据覆盖实时数据）
    // ================================================================

    /** 收盘历史落库后是否删除同日 REALTIME 行。 */
    public boolean overwriteRealtime() {
        return bool(CFG + "overwrite-realtime", true);
    }

    /** 当日实时数据的 task_type。 */
    public String realtimeTaskType() {
        return prop(CFG + "realtime-task-type", "REALTIME").trim();
    }

    /** 属于「收盘定稿」口径的 task_type 列表。 */
    public List<String> closeTaskTypes() {
        String raw = prop(CFG + "close-task-types", "QUOTE,NIGHT");
        return Arrays.stream(raw.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public boolean isCloseTaskType(String taskType) {
        if (taskType == null) {
            return false;
        }
        String t = taskType.trim().toUpperCase(Locale.ROOT);
        return closeTaskTypes().contains(t);
    }

    // ================================================================
    // 小工具
    // ================================================================

    private String prop(String key, String def) {
        String v = env == null ? null : env.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private boolean bool(String key, boolean def) {
        if (env == null) {
            return def;
        }
        Boolean v = env.getProperty(key, Boolean.class);
        return v == null ? def : v;
    }

    private List<String> list(String key) {
        String raw = prop(key, "");
        if (raw.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 配置键大小写兜底：YAML 里写 SHFE / shfe / Shfe 都能读到。 */
    private String firstProperty(String... keys) {
        for (String key : keys) {
            String v = env == null ? null : env.getProperty(key);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String normalize(String source) {
        return source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
    }

    private static String capitalize(String src) {
        if (src.isEmpty()) {
            return src;
        }
        return Character.toUpperCase(src.charAt(0)) + src.substring(1).toLowerCase(Locale.ROOT);
    }

    /** 额外留一个「周末判断」，某些不接 HolidaysApi 的调用方会用到。 */
    public static boolean isWeekend(LocalDate date) {
        if (date == null) {
            return false;
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }
}
