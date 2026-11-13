package com.zjysky.common.database.row;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CJ / SMM 两张现货行情表共用的「报价行」契约。
 *
 * <p>md_price_cj 与 md_price_smm 列结构完全一致，采集侧用同一套
 * Processor / Pipeline 解析，故此处只声明读写方法，由两个表实体实现。
 * 本接口不是数据库实体，不对应任何表。</p>
 */
public interface SpotPrice {

    String getTaskType();
    void setTaskType(String taskType);

    String getProductId();
    void setProductId(String productId);

    String getProductName();
    void setProductName(String productName);

    LocalDate getQuoteDate();
    void setQuoteDate(LocalDate quoteDate);

    BigDecimal getAvgPrice();
    void setAvgPrice(BigDecimal avgPrice);

    BigDecimal getHighPrice();
    void setHighPrice(BigDecimal highPrice);

    BigDecimal getLowPrice();
    void setLowPrice(BigDecimal lowPrice);

    BigDecimal getChangeValue();
    void setChangeValue(BigDecimal changeValue);

    BigDecimal getChangePct();
    void setChangePct(BigDecimal changePct);

    Integer getPointPrecision();
    void setPointPrecision(Integer pointPrecision);

    String getRawJson();
    void setRawJson(String rawJson);
}
