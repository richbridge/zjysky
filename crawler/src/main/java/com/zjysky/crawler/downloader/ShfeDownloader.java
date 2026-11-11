package com.zjysky.crawler.downloader;

/**
 * SHFE 专用 Downloader：只负责指定配置前缀是 {@code crawler.shfe.downloader.}。
 *
 * <p>真正的下载与落盘逻辑已经抽到 {@link RawDownloader}，这里只保留一个薄薄的壳，
 * 让 {@code ShfeSpider.create()} 的装配语句仍然一眼就能看懂：
 * {@code .setDownloader(ShfeDownloader.create())}。</p>
 *
 * <p>SHFE 的 .dat 是文本 JSON（不是 Excel），所以
 * {@code crawler.shfe.downloader.binary=false}、{@code extension=dat}，
 * 文件名会保留 URL 末尾的 {@code kx20251031.dat} / {@code delaymarket_all.dat}。</p>
 */
public class ShfeDownloader extends RawDownloader {

    private ShfeDownloader() {
        super("shfe");
    }

    public static ShfeDownloader create() {
        return new ShfeDownloader();
    }
}
