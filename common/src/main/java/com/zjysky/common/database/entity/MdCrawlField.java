package com.zjysky.common.database.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 字段字典：md_crawl_field。唯一键 (source, task_type, field_name)。 */
@Data
@TableName("md_crawl_field")
public class MdCrawlField {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源 SHFE / SGE / CJ / SMM。 */
    private String source;
    private String taskType;
    /** 源站原始字段名（翻译后为中文标准名）。 */
    private String fieldName;
    /** 映射到的表列名；未结构化的字段为 null。 */
    private String columnName;
    /** 非空率（百分比）。 */
    private Integer nonEmptyRate;
    /** 样例值（截断到 200 字符）。 */
    private String sampleValue;
    private LocalDateTime firstSeenTime;
    private LocalDateTime updateTime;
}
