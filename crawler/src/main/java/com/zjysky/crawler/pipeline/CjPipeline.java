package com.zjysky.crawler.pipeline;

import com.zjysky.common.database.mapper.MdPriceCjMapper;
import com.zjysky.common.database.row.SpotPrice;
import org.apache.ibatis.session.SqlSession;

import java.util.List;

/**
 * 四大组件之一：Pipeline（CJ 长江有色现货报价 → md_price_cj）。
 *
 * <p>落库流程（合约字典 → 名称回填 → upsert → 元信息）全部在
 * {@link AbstractSpotPipeline}，本类只绑定本源的 Mapper。</p>
 */
public class CjPipeline extends AbstractSpotPipeline {

    public CjPipeline() {
        super("CJ", "crawler.cj.table", "md_price_cj");
    }

    @Override
    protected int upsertBatch(List<? extends SpotPrice> list) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(MdPriceCjMapper.class).upsertBatch(list);
        }
    }

    /** CJ 没有实时口径行（两次采集同 task_type 幂等覆盖），无需清理。 */
    @Override
    protected int deleteRealtimeByDate(String tradeDate, String realtimeTaskType) {
        return 0;
    }
}
