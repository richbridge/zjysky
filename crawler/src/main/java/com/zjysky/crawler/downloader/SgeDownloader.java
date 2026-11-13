package com.zjysky.crawler.downloader;

/**
 * SGE 上金所的 Downloader（每日行情 .xlsx 二进制 / 延时行情 HTML），配置前缀 crawler.sge.downloader.。
 *
 * <p>共享逻辑全部在 {@link AbstractSourceDownloader}（下载、Accept 头、
 * 原始响应落盘、文件命名），本类只负责绑定自己的配置前缀。</p>
 */
public class SgeDownloader extends AbstractSourceDownloader {

    /** 常规采集：crawler.sge.downloader.*（xlsx，binary=true）。 */
    public SgeDownloader() {
        super("sge");
    }

    /** 子配置采集：crawler.{subKey}.downloader.*。 */
    public SgeDownloader(String subKey) {
        super(subKey);
    }

    /** 常规采集实例。 */
    public static SgeDownloader create() {
        return new SgeDownloader();
    }

    /** 当日延时行情（sge.realtime 子配置）：HTML 页面，文本模式。 */
    public static SgeDownloader forRealtime() {
        return new SgeDownloader("sge.realtime");
    }
}
