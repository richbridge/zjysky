package com.zjysky.common.database.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjysky.common.database.entity.MdPriceSge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** md_price_sge（上金所日行情）。 */
@Mapper
public interface MdPriceSgeMapper extends BaseMapper<MdPriceSge> {

    /** 批量 upsert，唯一键 (task_type, trade_date, contract)。 */
    int upsertBatch(@Param("list") List<MdPriceSge> list);

    /** 收盘数据定稿后，删除同交易日的 REALTIME 行。 */
    int deleteRealtimeByDate(@Param("tradeDate") String tradeDate,
                             @Param("realtimeTaskType") String realtimeTaskType);
}
