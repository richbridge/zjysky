package com.zjysky.common.database.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zjysky.common.database.row.SpotPrice;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** SMM 上海有色网现货报价：md_price_smm。唯一键 (task_type, quote_date, product_id)。 */
@Data
@TableName("md_price_smm")
public class MdPriceSmm implements SpotPrice {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务类型：CJ 默认 QUOTE，SMM 默认 NIGHT。 */
    private String taskType;
    private String productId;
    private String productName;
    private LocalDate quoteDate;
    private BigDecimal avgPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal changeValue;
    /** 涨跌幅（%），vchange_rate 是小数比例，已乘 100。 */
    private BigDecimal changePct;
    private Integer pointPrecision;
    /** 原始 JSON 行，未映射到列的展示字段全部保留。 */
    private String rawJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
