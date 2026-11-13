package com.zjysky.common.database.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 合约（品种）字典：md_source_product。唯一键 (source, product_id)。 */
@Data
@TableName("md_source_product")
public class MdSourceProduct {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源 SHFE / SGE / CJ / SMM。 */
    private String source;
    private String productId;
    private String productName;
    private String productGroup;
    /** 是否启用（1 启用）。 */
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
