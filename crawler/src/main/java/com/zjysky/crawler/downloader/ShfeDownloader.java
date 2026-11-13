package com.zjysky.crawler.downloader;

/**
 * SHFE 上期所的 Downloader（历史 .dat / 当日延时 .dat），配置前缀 crawler.shfe.downloader.。
 *
 * <p>共享逻辑全部在 {@link AbstractSourceDownloader}（下载、Accept 头、
 * 原始响应落盘、文件命名），本类只负责绑定自己的配置前缀。</p>
 */
public class ShfeDownloader extends AbstractSourceDownloader {

    /** 常规采集：crawler.shfe.downloader.*。 */
    public ShfeDownloader() {
        super("shfe");
    }

    /** 子配置采集：crawler.{subKey}.downloader.*。 */
    public ShfeDownloader(String subKey) {
        super(subKey);
    }

    /** 常规采集实例。 */
    public static ShfeDownloader create() {
        return new ShfeDownloader();
    }
}
