package com.zjysky.common.database.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 采集日志：md_crawl_log，每次采集一条流水。 */
@Data
@TableName("md_crawl_log")
public class MdCrawlLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String source;
    private String taskType;
    /** 状态：1 成功 / 0 失败。 */
    private Integer status;
    private Integer records;
    private Integer requests;
    private Integer pages;
    /** 耗时（毫秒）。 */
    private Integer costMs;
    /** 摘要（截断到 500 字符）。 */
    private String message;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;
}
