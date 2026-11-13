package com.zjysky.crawler.downloader;

/**
 * CJ 长江有色的 Downloader（现货历史 JSON / 品种发现 HTML），配置前缀 crawler.cj.downloader.。
 *
 * <p>共享逻辑全部在 {@link AbstractSourceDownloader}（下载、Accept 头、
 * 原始响应落盘、文件命名），本类只负责绑定自己的配置前缀。</p>
 */
public class CjDownloader extends AbstractSourceDownloader {

    /** 常规采集：crawler.cj.downloader.*（行情 JSON）。 */
    public CjDownloader() {
        super("cj");
    }

    /** 子配置采集：crawler.{subKey}.downloader.*。 */
    public CjDownloader(String subKey) {
        super(subKey);
    }

    /** 常规采集实例。 */
    public static CjDownloader create() {
        return new CjDownloader();
    }

    /** 品种发现页（cj.product 子配置）：HTML，默认不落盘。 */
    public static CjDownloader forProducts() {
        return new CjDownloader("cj.product");
    }
}
