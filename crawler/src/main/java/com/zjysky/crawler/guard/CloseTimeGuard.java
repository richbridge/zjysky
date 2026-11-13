package com.zjysky.crawler.guard;

import com.zjysky.common.utils.MicaHolidays;
import com.zjysky.crawler.CrawlerConfig;
import net.dreamlu.mica.holidays.config.HolidaysApiProperties;
import net.dreamlu.mica.holidays.impl.HolidaysApiImpl;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 收盘 / 交易日守卫：crawler 侧所有「哪天开市、几点收盘」的判断入口。
 *
 * <p><b>工作日统一来自 common 模块</b>：本类把交易日判断委托给
 * {@link MicaHolidays}，它使用 mica 官方 2019~2026 法定节假日数据，
 * 能正确识别调休上班日与法定假日。crawler 不再自己用 {@code DayOfWeek}
 * 猜周末，与 admin 侧口径完全一致（同一份数据、同一套方法）。</p>
 *
 * <p><b>为什么不直接注入</b>：crawler 是纯 Java SE 模块、不启 Spring 容器，
 * 而 {@code MicaHolidays} 是 Spring {@code @Service}，构造需要
 * {@code HolidaysApi} 与 {@code Environment}。这里手工构造
 * {@link HolidaysApiImpl} 并调用 {@code afterPropertiesSet()} 加载节假日数据，
 * 再把 {@link CrawlerConfig#effective()} 的生效配置灌进
 * {@link StandardEnvironment}，这样 common 侧仍能读到
 * {@code crawler.market-close.*} 的收盘时间、节假日覆盖等配置。</p>
 *
 * <p>除日历委托外，本类还保留两项 crawler 特有的守卫能力：
 * ① 收盘数据「定稿」判定（{@code finalized*}，防止盘中半成品入库）；
 * ② 四源状态打印（{@code market-status} 命令）。</p>
 */
public final class CloseTimeGuard {

    private static final List<String> SOURCES = List.of("SHFE", "SGE", "CJ", "SMM");

    /** common 侧唯一的工作日 / 收盘日历，进程内单例。 */
    private static final MicaHolidays CALENDAR = createCalendar();

    private CloseTimeGuard() {
    }

    private static MicaHolidays createCalendar() {
        try {
            HolidaysApiImpl api = new HolidaysApiImpl(
                    new DefaultResourceLoader(), new HolidaysApiProperties());
            api.afterPropertiesSet(); // 加载 classpath:data/2019~2026_data.json
            StandardEnvironment env = new StandardEnvironment();
            env.getPropertySources().addFirst(
                    new MapPropertySource("crawler", CrawlerConfig.effective()));
            return new MicaHolidays(api, env);
        } catch (Exception e) {
            throw new IllegalStateException("加载 common 节假日库失败", e);
        }
    }

    /** 需要完整日历能力（isWorkday / getPreviousWorkdayYyyyMmDd 等）时直接取用。 */
    public static MicaHolidays calendar() {
        return CALENDAR;
    }

    // ================================================================
    // 1. 交易日 / 收盘判断 —— 全部委托 common.MicaHolidays
    // ================================================================

    /** 是否启用收盘守卫；关掉后所有 shouldCollectHistory 都放行（手动补历史用）。 */
    public static boolean enabled() {
        return CALENDAR.enabled();
    }

    /** 时区，默认 Asia/Shanghai。 */
    public static ZoneId zone() {
        return CALENDAR.zone();
    }

    /** 当前时刻（按配置时区）。 */
    public static LocalDateTime now() {
        return CALENDAR.now();
    }

    /** 是否交易日：mica 官方节假日数据 + 配置里的节假日 / 调休覆盖。 */
    public static boolean isTradeDate(LocalDate date) {
        return CALENDAR.isTradeDate(date);
    }

    /** 是否工作日（法定节假日为 false，调休上班日为 true）。 */
    public static boolean isWorkday(LocalDate date) {
        return CALENDAR.isWorkday(date);
    }

    /** 上一工作日；含法定节假日与调休，数据来自 common。 */
    public static LocalDate previousWorkday(LocalDate from) {
        return CALENDAR.getPreviousWorkday(from);
    }

    /** 某个源的收盘时间。 */
    public static LocalTime closeTime(String source) {
        return CALENDAR.closeTime(source);
    }

    /** 现在是否该采这个源的收盘历史数据。 */
    public static boolean shouldCollectHistory(String source, LocalDateTime now) {
        return CALENDAR.shouldCollectHistory(source, now);
    }

    public static boolean shouldCollectHistory(String source) {
        return CALENDAR.shouldCollectHistory(source);
    }

    /** 是否处于盘中（交易日且未到收盘时间）。 */
    public static boolean isIntraday(String source, LocalDateTime now) {
        return CALENDAR.isIntraday(source, now);
    }

    public static boolean isIntraday(String source) {
        return CALENDAR.isIntraday(source);
    }

    /** 是否允许当日实时采集（只有 SHFE / SGE 有实时口径）。 */
    public static boolean supportsRealtime(String source) {
        return CALENDAR.supportsRealtime(source);
    }

    /** 上一个已收盘交易日，yyyy-MM-dd。 */
    public static String lastClosedTradeDate(String source, LocalDateTime now) {
        return CALENDAR.lastClosedTradeDate(source, now).toString();
    }

    public static String lastClosedTradeDate(String source) {
        return CALENDAR.lastClosedTradeDate(source).toString();
    }

    /** 收盘数据落库后是否删除同日 REALTIME 行。 */
    public static boolean overwriteRealtime() {
        return CALENDAR.overwriteRealtime();
    }

    /** 当日实时数据的 task_type。 */
    public static String realtimeTaskType() {
        return CALENDAR.realtimeTaskType();
    }

    /** 属于「收盘定稿」口径的 task_type 列表。 */
    public static List<String> closeTaskTypes() {
        return CALENDAR.closeTaskTypes();
    }

    public static boolean isCloseTaskType(String taskType) {
        return CALENDAR.isCloseTaskType(taskType);
    }

    /** 某源当前采集窗口的中文说明。 */
    public static String status(String source, LocalDateTime now) {
        return CALENDAR.status(source, now);
    }

    public static String status(String source) {
        return CALENDAR.status(source);
    }

    // ================================================================
    // 2. 四源状态打印（crawler CLI 的 market-status 命令）
    // ================================================================

    public static void printAllStatus() {
        LocalDateTime at = now();
        System.out.println("=== 各源收盘时间 / 采集窗口（" + at + " " + zone() + "）===");
        Map<String, String> rows = new LinkedHashMap<>();
        for (String s : SOURCES) {
            rows.put(s, status(s, at));
        }
        rows.forEach((k, v) -> System.out.println("  " + k + " : " + v));
        System.out.println("  覆盖策略 : 收盘落库后删除当日 REALTIME = " + overwriteRealtime()
                + "，实时口径 task_type=" + realtimeTaskType()
                + "，收盘口径=" + closeTaskTypes());
        System.out.println("  交易日历 : common/MicaHolidays（mica 官方 2019~2026，含调休）"
                + "，今日交易日=" + isTradeDate(at.toLocalDate())
                + "，上一工作日=" + previousWorkday(at.toLocalDate()));
        System.out.println("  配置覆盖 : holidays=" + CALENDAR.holidays()
                + "，extra-workdays=" + CALENDAR.extraWorkdays());
    }

    // ================================================================
    // 3. 收盘数据「定稿」判定（common 没有，crawler 落库前特有守卫）
    // ================================================================
    // SHFE 的 kx{yyyyMMdd}.dat 在盘中也会返回 200，但内容是半成品
    // （实测盘中那份 332 个合约里收盘价只有 4 个、结算价 0 个）。
    // 拿半成品当收盘数据入库会污染当天价格、还会误删有效的实时行，
    // 所以 Pipeline 落库前先在这里问一次：不定稿就跳过本次落库。

    /**
     * 通用判定：有行情的记录必须过半，否则认为「只出了个开头」。
     *
     * @param total  本次解析出的记录数
     * @param priced 其中收盘价非空的记录数
     */
    public static boolean finalizedByRatio(int total, int priced) {
        return total > 0 && priced > 0 && priced * 2 >= total;
    }

    /**
     * SHFE 专用判定：结算价（SETTLEMENTPRICE）收盘后才公布，盘中那份全是空，
     * 所以「有结算价」且「有收盘价的过半」才算定稿。
     */
    public static boolean finalizedShfe(int total, int priced, int settled) {
        return settled > 0 && finalizedByRatio(total, priced);
    }

    /** 给日志用的说明文本。 */
    public static String describeFinalized(int total, int priced, int settled) {
        return "记录 " + total + " 条，其中有收盘价 " + priced + " 条、有结算价 " + settled + " 条";
    }
}
