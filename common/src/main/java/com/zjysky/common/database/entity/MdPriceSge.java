package com.zjysky.common.database.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 上金所日行情：md_price_sge。唯一键 (task_type, trade_date, contract)。 */
@Data
@TableName("md_price_sge")
public class MdPriceSge {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskType;
    private LocalDate tradeDate;
    private String contract;
    private Integer seqNo;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal closePrice;
    private BigDecimal settlePrice;
    private BigDecimal avgPrice;
    private BigDecimal changeValue;
    private BigDecimal changePct;
    private BigDecimal volume;
    private BigDecimal openInterest;
    private String deliveryDirection;
    private BigDecimal deliveryVolume;
    private BigDecimal turnover;
    private String rawJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
