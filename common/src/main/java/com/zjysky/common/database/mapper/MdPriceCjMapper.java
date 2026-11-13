package com.zjysky.common.database.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjysky.common.database.entity.MdPriceCj;
import com.zjysky.common.database.row.SpotPrice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/** md_price_cj（CJ 长江有色现货报价）。 */
@Mapper
public interface MdPriceCjMapper extends BaseMapper<MdPriceCj> {

    List<MdPriceCj> findTodayData(@Param("date") LocalDate date);

    /** 批量 upsert 到本表，唯一键 (task_type, quote_date, product_id)。 */
    int upsertBatch(@Param("list") List<? extends SpotPrice> list);
}
