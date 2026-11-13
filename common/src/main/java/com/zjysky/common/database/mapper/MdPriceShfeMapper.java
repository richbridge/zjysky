package com.zjysky.common.database.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjysky.common.database.entity.MdPriceShfe;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** md_price_shfe（上期所日行情）。 */
@Mapper
public interface MdPriceShfeMapper extends BaseMapper<MdPriceShfe> {

    /** 批量 upsert，唯一键 (task_type, trade_date, symbol)。 */
    int upsertBatch(@Param("list") List<MdPriceShfe> list);

    /** 收盘数据定稿后，删除同交易日的 REALTIME 行。 */
    int deleteRealtimeByDate(@Param("tradeDate") String tradeDate,
                             @Param("realtimeTaskType") String realtimeTaskType);
}
