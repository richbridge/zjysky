package com.zjysky.common.database.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjysky.common.database.entity.MdPriceSmm;
import com.zjysky.common.database.row.SpotPrice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** md_price_smm（SMM 上海有色网现货报价）。 */
@Mapper
public interface MdPriceSmmMapper extends BaseMapper<MdPriceSmm> {

    /** 批量 upsert 到本表，唯一键 (task_type, quote_date, product_id)。 */
    int upsertBatch(@Param("list") List<? extends SpotPrice> list);
}
