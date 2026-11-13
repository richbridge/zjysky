package com.zjysky.crawler.spider;

import com.zjysky.crawler.SpotSource;
import com.zjysky.crawler.downloader.CjDownloader;
import com.zjysky.crawler.downloader.AbstractSourceDownloader;
import com.zjysky.crawler.processor.AbstractSpotProcessor;
import com.zjysky.crawler.processor.CjProcessor;


/**
 * CJ 长江有色爬虫：四个组件都来自 cj 专属实现
 * （CjProcessor / CjDownloader / QueueScheduler / CjPipeline）。
 *
 * <p>常用入口（都由 {@link AbstractSpotSpider} 提供）：</p>
 * <pre>
 * CjSpider.runAll(null, null)        抓全部合约最新报价（定时盘中动作）
 * CjSpider.runAll("2025-11-06", ...) 抓指定日期收盘报价
 * CjSpider.runOne(productId, ...)    只抓一个合约
 * CjSpider.discoverProducts()        只做品种发现，写 md_source_product
 * </pre>
 */
public final class CjSpider extends AbstractSpotSpider {

    public CjSpider() {
        super(SpotSource.CJ);
    }

    @Override
    protected AbstractSpotProcessor newProcessor() {
        return new CjProcessor();
    }

    @Override
    protected AbstractSpotProcessor newProductProcessor() {
        return new CjProcessor();
    }

    @Override
    protected AbstractSourceDownloader newDownloader() {
        return CjDownloader.create();
    }

    @Override
    protected AbstractSourceDownloader newProductDownloader() {
        return CjDownloader.forProducts();
    }
}
