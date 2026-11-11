package com.zjysky.crawler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjysky.crawler.model.MdPriceShfe;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SHFE 行情 Mapper，对应库中已有宽表 {@code md_price_shfe}。
 *
 * <p>注意：该表是 digintel 遗留的统一价格表（已有其它业务数据），
 * 因此这里<b>不提供任何建表/删表 DDL</b>，只在运行前做字段完整性自检。</p>
 *
 * <p>自定义方法避开 MP 3.5.17 BaseMapper 内置的批量方法名，防止 MappedStatement 冲突。</p>
 *
 * <p><b>故意不标注 {@code @Mapper}</b>：本模块由 {@code DbConfig} 手工
 * {@code configuration.addMapper(ShfeMapper.class)} 注册；若加上 @Mapper，
 * admin（Spring Boot）的 MyBatis-Plus 自动扫描会把它注册成 Spring Bean，
 * 与手工装配的 SqlSessionFactory 冲突（Spring 7 下报 factoryBeanObjectType 类型错误）。</p>
 */
public interface ShfeMapper extends BaseMapper<MdPriceShfe> {

    /** 字段完整性自检：返回目标表的全部列名（来自 information_schema） */
    List<String> listColumns(@Param("tableName") String tableName);

    /** 按 uk_price(task_type, trade_date, symbol) 批量 upsert，同一交易日重复采集幂等 */
    int upsertBatch(@Param("list") List<MdPriceShfe> list);
}
