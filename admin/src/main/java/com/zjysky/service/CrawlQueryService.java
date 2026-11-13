package com.zjysky.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zjysky.common.database.entity.MdCrawlLog;
import com.zjysky.common.database.entity.MdPriceCj;
import com.zjysky.common.database.entity.MdPriceShfe;
import com.zjysky.common.database.entity.MdPriceSge;
import com.zjysky.common.database.entity.MdPriceSmm;
import com.zjysky.common.database.mapper.MdCrawlLogMapper;
import com.zjysky.common.database.mapper.MdPriceCjMapper;
import com.zjysky.common.database.mapper.MdPriceShfeMapper;
import com.zjysky.common.database.mapper.MdPriceSgeMapper;
import com.zjysky.common.database.mapper.MdPriceSmmMapper;
import com.zjysky.crawler.guard.CloseTimeGuard;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 前端「价格爬取」页的查询服务：只读，不参与采集。
 *
 * <p>实体与 Mapper 全部来自 common（与 crawler 写入的是同一套），service 由 admin 自己实现，
 * 符合「持久层收敛、业务层各模块自持」的分层。</p>
 */
@Service
public class CrawlQueryService {

    private static final List<String> SOURCES = List.of("SHFE", "SGE", "CJ", "SMM");

    private final MdCrawlLogMapper crawlLogMapper;
    private final MdPriceCjMapper cjMapper;
    private final MdPriceSmmMapper smmMapper;
    private final MdPriceSgeMapper sgeMapper;
    private final MdPriceShfeMapper shfeMapper;

    public CrawlQueryService(MdCrawlLogMapper crawlLogMapper,
                             MdPriceCjMapper cjMapper,
                             MdPriceSmmMapper smmMapper,
                             MdPriceSgeMapper sgeMapper,
                             MdPriceShfeMapper shfeMapper) {
        this.crawlLogMapper = crawlLogMapper;
        this.cjMapper = cjMapper;
        this.smmMapper = smmMapper;
        this.sgeMapper = sgeMapper;
        this.shfeMapper = shfeMapper;
    }

    /** 四源当前状态：交易日 / 盘中 / 收盘时间 / 最近已收盘交易日 / 中文说明。 */
    public List<Map<String, Object>> status() {
        LocalDateTime now = CloseTimeGuard.now();
        LocalDate today = now.toLocalDate();
        return SOURCES.stream().map(source -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", source);
            row.put("now", now.toString());
            row.put("tradeDate", today.toString());
            row.put("isTradeDate", CloseTimeGuard.isTradeDate(today));
            row.put("isIntraday", CloseTimeGuard.isIntraday(source, now));
            row.put("closeTime", CloseTimeGuard.closeTime(source).toString());
            row.put("lastClosedTradeDate", CloseTimeGuard.lastClosedTradeDate(source, now));
            row.put("supportsRealtime", CloseTimeGuard.supportsRealtime(source));
            row.put("status", CloseTimeGuard.status(source, now));
            row.put("overwriteRealtime", CloseTimeGuard.overwriteRealtime());
            return row;
        }).toList();
    }

    /** 最近采集日志（md_crawl_log），source 为空表示不限源。 */
    public List<MdCrawlLog> logs(String source, int limit) {
        String src = source == null || source.isBlank()
                ? "" : source.trim().toUpperCase(Locale.ROOT);
        return crawlLogMapper.listLogs(src.isBlank() ? null : src, null, safeLimit(limit));
    }

    /**
     * 指定源、指定日期的价格数据（md_price_* 四张表）。
     * 返回 common 的实体列表，字段由前端按源选择列展示。
     */
    public List<?> prices(String source, String date, int limit) {
        String src = normalizeSource(source);
        LocalDate day;
        try {
            day = date == null || date.isBlank()
                    ? LocalDate.now() : LocalDate.parse(date.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式不对：" + date + "，应为 yyyy-MM-dd");
        }
        int max = safeLimit(limit);
        String tail = "LIMIT " + max;
        return switch (src) {
            case "CJ" -> cjMapper.selectList(new QueryWrapper<MdPriceCj>()
                    .eq("quote_date", day).orderByDesc("id").last(tail));
            case "SMM" -> smmMapper.selectList(new QueryWrapper<MdPriceSmm>()
                    .eq("quote_date", day).orderByDesc("id").last(tail));
            case "SGE" -> sgeMapper.selectList(new QueryWrapper<MdPriceSge>()
                    .eq("trade_date", day).orderByDesc("id").last(tail));
            default -> shfeMapper.selectList(new QueryWrapper<MdPriceShfe>()
                    .eq("trade_date", day).orderByDesc("id").last(tail));
        };
    }

    private static String normalizeSource(String source) {
        String src = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(src)) {
            throw new IllegalArgumentException("未知数据源 " + source + "，可用 SHFE / SGE / CJ / SMM");
        }
        return src;
    }

    private static int safeLimit(int limit) {
        return Math.max(1, Math.min(limit, 500));
    }
}
