package com.zjysky.common.utils;

import net.dreamlu.mica.holidays.core.HolidaysApi;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class MicaHolidays {

    private final HolidaysApi holidaysApi;

    public MicaHolidays(HolidaysApi holidaysApi) {
        this.holidaysApi = holidaysApi;
    }

    public boolean isWorkday(LocalDate date) {
        return holidaysApi.isWeekdays(date);
    }

    // ================= 核心：获取上一工作日的 LocalDate 对象 =================

    /**
     * 获取上一工作日（核心方法，返回 LocalDate）
     */
    public LocalDate getPreviousWorkday(LocalDate date) {
        LocalDate previousDay = date.minusDays(1);
        int maxAttempts = 30;

        for (int i = 0; i < maxAttempts; i++) {
            if (isWorkday(previousDay)) {
                return previousDay;
            }
            previousDay = previousDay.minusDays(1);
        }
        return null;
    }

    // ================= 封装：返回指定格式的字符串 =================

    /**
     * 获取上一工作日，返回 yyyyMMdd 格式（例：20261109）
     */
    public String getPreviousWorkdayYyyyMMdd(LocalDate date) {
        LocalDate result = getPreviousWorkday(date);
        if (result == null) return null;
        return result.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    /**
     * 获取上一工作日，返回 yyyy-MM-dd 格式（例：2026-11-09）
     */
    public String getPreviousWorkdayYyyyMmDd(LocalDate date) {
        LocalDate result = getPreviousWorkday(date);
        if (result == null) return null;
        return result.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
}