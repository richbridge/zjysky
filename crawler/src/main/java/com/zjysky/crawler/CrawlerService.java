package com.zjysky.crawler;

import com.zjysky.crawler.config.CrawlerConfig;
import com.zjysky.crawler.processor.ShfeProcessor;
import com.zjysky.crawler.scheduler.ShfeScheduler;

/**
 * 统一启动入口。配置统一在 {@code crawler/src/main/resources/application.yml}。
 *
 * <pre>
 * java com.zjysky.crawler.CrawlerService                    # 按 application.yml 的 schedule 配置定时爬取
 * java com.zjysky.crawler.CrawlerService once [url]       # 单次采集并落库 md_price_shfe
 * java com.zjysky.crawler.CrawlerService download [url]   # 只用 Downloader 下载当日/指定日 JSON 落盘，不入库
 * java com.zjysky.crawler.CrawlerService https://...      # 直接传 URL 也是单次采集
 * java com.zjysky.crawler.CrawlerService 09:30            # 命令行临时覆盖定时时刻
 * java com.zjysky.crawler.CrawlerService config           # 打印生效配置（密码打码）后退出
 * </pre>
 */
public class CrawlerService {

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "";

        if ("config".equalsIgnoreCase(mode)) {
            CrawlerConfig.dump();
            return;
        }
        if ("download".equalsIgnoreCase(mode)) {
            String url = args.length > 1 ? args[1] : ShfeProcessor.urlForToday();
            ShfeProcessor.downloadOnly(url);
            return;
        }
        if ("once".equalsIgnoreCase(mode)) {
            String url = args.length > 1 ? args[1] : ShfeProcessor.defaultUrl();
            ShfeProcessor.runOnce(url);
            return;
        }
        if (mode.startsWith("http")) {
            ShfeProcessor.runOnce(mode);
            return;
        }

        // 未开启定时则退化为单次采集，避免启动后什么都不做
        if (!CrawlerConfig.boolVal("crawler.schedule.enabled", true)) {
            ShfeProcessor.runOnce(ShfeProcessor.defaultUrl());
            return;
        }

        ShfeScheduler scheduler = new ShfeScheduler();
        if (mode.contains(":")) {
            String[] hm = mode.split(":");
            scheduler.startScheduled(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
        } else {
            scheduler.startScheduled(); // 时刻取自 application.yml
        }
        Runtime.getRuntime().addShutdownHook(new Thread(scheduler::stop));
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
