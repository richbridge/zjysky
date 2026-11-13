package com.zjysky.crawler.spider;

import com.zjysky.crawler.SpotSource;
import com.zjysky.crawler.downloader.AbstractSourceDownloader;
import com.zjysky.crawler.downloader.SmmDownloader;
import com.zjysky.crawler.processor.AbstractSpotProcessor;
import com.zjysky.crawler.processor.SmmProcessor;


/**
 * SMM 上海有色网爬虫：四个组件都来自 smm 专属实现
 * （SmmProcessor / SmmDownloader / QueueScheduler / SmmPipeline）。
 *
 * <p>常用入口（都由 {@link AbstractSpotSpider} 提供）：</p>
 * <pre>
 * SmmSpider.runAll(null, null)       抓全部合约最新报价（定时盘中动作）
 * SmmSpider.runAll("2025-11-19", ...) 抓指定日期收盘报价
 * SmmSpider.runOne(productId, ...)    只抓一个合约
 * SmmSpider.discoverProducts()        只做品种发现，写 md_source_product
 * </pre>
 */
public final class SmmSpider extends AbstractSpotSpider {

    public SmmSpider() {
        super(SpotSource.SMM);
    }

    @Override
    protected AbstractSpotProcessor newProcessor() {
        return new SmmProcessor();
    }

    @Override
    protected AbstractSpotProcessor newProductProcessor() {
        return new SmmProcessor();
    }

    @Override
    protected AbstractSourceDownloader newDownloader() {
        return SmmDownloader.create();
    }

    @Override
    protected AbstractSourceDownloader newProductDownloader() {
        return SmmDownloader.forProducts();
    }
}
