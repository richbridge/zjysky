package com.zjysky.crawler.downloader;

/**
 * SMM 上海有色网的 Downloader（现货历史 JSON / 品种发现 HTML），配置前缀 crawler.smm.downloader.。
 *
 * <p>共享逻辑全部在 {@link AbstractSourceDownloader}（下载、Accept 头、
 * 原始响应落盘、文件命名），本类只负责绑定自己的配置前缀。</p>
 */
public class SmmDownloader extends AbstractSourceDownloader {

    /** 常规采集：crawler.smm.downloader.*（行情 JSON）。 */
    public SmmDownloader() {
        super("smm");
    }

    /** 子配置采集：crawler.{subKey}.downloader.*。 */
    public SmmDownloader(String subKey) {
        super(subKey);
    }

    /** 常规采集实例。 */
    public static SmmDownloader create() {
        return new SmmDownloader();
    }

    /** 品种发现页（smm.product 子配置）：HTML，默认不落盘。 */
    public static SmmDownloader forProducts() {
        return new SmmDownloader("smm.product");
    }
}
