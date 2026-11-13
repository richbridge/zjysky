package com.zjysky.common.database.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjysky.common.database.entity.MdSourceProduct;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** md_source_product（合约 / 品种字典）。 */
@Mapper
public interface MdSourceProductMapper extends BaseMapper<MdSourceProduct> {

    /** 批量 upsert，唯一键 (source, product_id)。 */
    int upsertProducts(@Param("list") List<MdSourceProduct> list);

    /** 某数据源已发现的合约。 */
    List<MdSourceProduct> listProducts(@Param("source") String source);

    /** 按合约 id 反查名称。 */
    String findProductName(@Param("source") String source,
                           @Param("productId") String productId);
}
