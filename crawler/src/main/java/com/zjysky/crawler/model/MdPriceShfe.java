package com.zjysky.crawler.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * SHFE 日线行情实体（Lombok + MyBatis-Plus），严格对齐库中已有宽表 {@code md_price_shfe}。
 *
 * <p>字段名/类型与该表一一对应：价格用 {@link BigDecimal}（表为 decimal(24,6)），
 * 量额用 {@link BigDecimal}（表为 decimal(24,4)），tradeDate 传 {@code yyyy-MM-dd}
 * 由 MySQL 隐式转 DATE。表中未被 SHFE 日线使用的列（bid_price / ask_price 等）保留字段，
 * 供 intraday/settle 任务复用，写入时为 null。</p>
 *
 * <p>必填：taskType、symbol（表为 NOT NULL 且无默认值），tradeDate、rowType 由采集端补齐。
 * 唯一键 {@code uk_price(task_type, trade_date, symbol)}。</p>
 */
@Data
@TableName("md_price_shfe")
public class MdPriceShfe {

    @TableId(type = IdType.AUTO)
    private Long id;                    // 自增主键

    private String taskType;            // QUOTE / INTRADAY / SETTLE（必填）
    private String rowType;             // CONTRACT / PRODUCT / INDEX / TOTAL
    private String tradeDate;           // yyyy-MM-dd → DATE 列
    private String symbol;              // 唯一键之一（必填）：品种-交割月 / 品种 / 指数名

    private String contractMonth;       // DELIVERYMONTH
    private String productName;         // PRODUCTNAME
    private String productId;           // PRODUCTID
    private String productGroup;        // PRODUCTGROUPID
    private String productClass;        // PRODUCTCLASS

    private BigDecimal openPrice;       // OPENPRICE
    private BigDecimal highPrice;       // HIGHESTPRICE
    private BigDecimal lowPrice;        // LOWESTPRICE
    private BigDecimal closePrice;      // CLOSEPRICE
    private BigDecimal lastPrice;       // LASTPRICE（intraday）
    private BigDecimal preClosePrice;   // PRECLOSEPRICE（指数/汇总）
    private BigDecimal settlePrice;     // SETTLEMENTPRICE
    private BigDecimal preSettlePrice;  // PRESETTLEMENTPRICE
    private BigDecimal avgPrice;        // AVGPRICE（品种汇总/指数）
    private BigDecimal changeValue;     // ZD1_CHG / UPDOWN
    private BigDecimal changeValue2;    // ZD2_CHG / UPDOWN2
    private BigDecimal changePct;       // 涨跌幅（SHFE 未提供）

    private BigDecimal volume;          // VOLUME
    private BigDecimal tasVolume;       // TASVOLUME（原始可能为空串 → null）
    private BigDecimal lastVolume;      // lastv（intraday）
    private BigDecimal openInterest;    // OPENINTEREST
    private BigDecimal openInterestChange; // OPENINTERESTCHG
    private BigDecimal turnover;        // TURNOVER
    private BigDecimal yearVolume;      // YEARVOLUME（品种汇总）
    private BigDecimal yearTurnover;    // YEARTURNOVER（品种汇总）

    private BigDecimal bidPrice;        // 买价（intraday）
    private BigDecimal askPrice;        // 卖价（intraday）
    private String sourceUpdateTime;    // 源站 update_date
    private String rawJson;             // 该行原始数据全字段（未映射字段也保留在此）

    // create_time / update_time 由数据库默认值维护，不写入

    private static String s(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }

    @Override
    public String toString() {
        return String.format("%s [%s] %-14s 开:%s 高:%s 低:%s 收:%s 结算:%s 量:%s 额:%s",
                tradeDate, rowType, symbol,
                s(openPrice), s(highPrice), s(lowPrice), s(closePrice), s(settlePrice),
                s(volume), s(turnover));
    }
}
