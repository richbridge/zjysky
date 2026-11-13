package com.zjysky.common.database.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjysky.common.database.entity.MdCrawlLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** md_crawl_log 采集日志。 */
@Mapper
public interface MdCrawlLogMapper extends BaseMapper<MdCrawlLog> {

    /** 追加一条流水（只 INSERT）。 */
    int insertLog(MdCrawlLog log);

    /** 最近采集日志，可按 source / task_type 过滤。 */
    List<MdCrawlLog> listLogs(@Param("source") String source,
                              @Param("taskType") String taskType,
                              @Param("limit") int limit);
}
