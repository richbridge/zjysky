package com.zjysky.common.database.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 上期所日行情：md_price_shfe。唯一键 (task_type, trade_date, symbol)。 */
@Data
@TableName("md_price_shfe")
public class MdPriceShfe {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskType;
    private String rowType;
    private LocalDate tradeDate;
    private String symbol;
    private String contractMonth;
    private String productName;
    private String productId;
    private String productGroup;
    private String productClass;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal closePrice;
    private BigDecimal lastPrice;
    private BigDecimal preClosePrice;
    private BigDecimal settlePrice;
    private BigDecimal preSettlePrice;
    private BigDecimal avgPrice;
    private BigDecimal changeValue;
    private BigDecimal changeValue2;
    private BigDecimal changePct;
    private BigDecimal volume;
    private BigDecimal tasVolume;
    private BigDecimal lastVolume;
    private BigDecimal openInterest;
    private BigDecimal openInterestChange;
    private BigDecimal turnover;
    private BigDecimal yearVolume;
    private BigDecimal yearTurnover;
    private BigDecimal bidPrice;
    private BigDecimal askPrice;
    private String sourceUpdateTime;
    private String rawJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
