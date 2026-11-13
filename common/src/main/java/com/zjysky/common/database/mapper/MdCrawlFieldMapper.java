package com.zjysky.common.database.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjysky.common.database.entity.MdCrawlField;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** md_crawl_field 字段字典。 */
@Mapper
public interface MdCrawlFieldMapper extends BaseMapper<MdCrawlField> {

    /** 批量 upsert，唯一键 (source, task_type, field_name)。 */
    int upsertFields(@Param("list") List<MdCrawlField> list);

    /** 字段字典，可按 source / task_type 过滤。 */
    List<MdCrawlField> listFields(@Param("source") String source,
                                  @Param("taskType") String taskType);
}
