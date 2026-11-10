package com.zjysky.crawler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjysky.crawler.model.ShfeDailyData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SHFE 行情 Mapper，对应库中已有宽表 {@code md_price_shfe}。
 *
 * <p>注意：该表是 digintel 遗留的统一价格表（已有其它业务数据），
 * 因此这里<b>不提供任何建表/删表 DDL</b>，只在运行前做字段完整性自检。</p>
 *
 * <p>自定义方法避开 MP 3.5.17 BaseMapper 内置的批量方法名，防止 MappedStatement 冲突。</p>
 */
@Mapper
public interface ShfeMapper extends BaseMapper<ShfeDailyData> {

    /** 字段完整性自检：返回目标表的全部列名（来自 information_schema） */
    List<String> listColumns(@Param("tableName") String tableName);

    /** 按 uk_price(task_type, trade_date, symbol) 批量 upsert，同一交易日重复采集幂等 */
    int upsertBatch(@Param("list") List<ShfeDailyData> list);
}
