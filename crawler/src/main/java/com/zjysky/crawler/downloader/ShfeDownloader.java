package com.zjysky.crawler.downloader;

import com.zjysky.crawler.config.CrawlerConfig;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.downloader.HttpClientDownloader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 四大组件之一：Downloader。
 *
 * <p>职责：下载 SHFE 每日 .dat（实质是完整 JSON），并做两件事：</p>
 * <ol>
 *   <li>把原始报文放进 {@code Page.rawText} 交给 PageProcessor 解析；</li>
 *   <li><b>把每日完整 JSON 落盘保存</b>到 {@code crawler.shfe.downloader.save-dir}，
 *       文件名为 URL 末尾的 {@code kx{yyyyMMdd}.dat}。这样顶层元数据
 *       （report_date / update_date / o_weekday / o_total_num 等）和三类数据集
 *       （合约 / 品种汇总 / 金属指数）都完整留存，不受数据库表列限制，也便于断点续采与回溯。</li>
 * </ol>
 *
 * <p>WebMagic 1.0.3 的 HttpClientDownloader 没有 setHttpClient 注入入口，
 * 超时 / 编码 / UA / Referer 统一在 ShfeProcessor 的 Site 中配置。</p>
 */
public class ShfeDownloader extends HttpClientDownloader {

    private static final String CFG = "crawler.shfe.downloader.";

    public static ShfeDownloader create() {
        return new ShfeDownloader();
    }

    @Override
    public Page download(Request request, Task task) {
        request.addHeader("Accept", "application/json, text/plain, */*");
        Page page = super.download(request, task);
        String raw = page == null ? null : page.getRawText();
        if (raw == null || raw.isBlank()) {
            System.err.println(">>> [Downloader] JSON 下载失败或为空: " + request.getUrl());
            return page;
        }
        System.out.println(">>> [Downloader] JSON 下载完成: " + request.getUrl()
                + " (" + raw.length() + " 字符)");
        saveDailyJson(request.getUrl(), raw);
        return page;
    }

    /** 把每日完整原始 JSON 落盘（UTF-8），失败只告警、不影响后续解析入库 */
    private void saveDailyJson(String url, String raw) {
        if (!CrawlerConfig.boolVal(CFG + "save-enabled", true)) {
            return;
        }
        try {
            String fileName = url.substring(url.lastIndexOf('/') + 1);
            if (fileName.isBlank() || !fileName.contains(".")) {
                fileName = "shfe-" + System.currentTimeMillis() + ".dat";
            }
            Path path = Paths.get(CrawlerConfig.str(CFG + "save-dir", "data/shfe"))
                    .toAbsolutePath().normalize().resolve(fileName);
            Files.createDirectories(path.getParent());

            if (CrawlerConfig.boolVal(CFG + "skip-existing", true)
                    && Files.exists(path) && Files.size(path) > 0) {
                System.out.println(">>> [Downloader] 每日 JSON 已存在，跳过保存: " + path);
                return;
            }
            Files.writeString(path, raw, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println(">>> [Downloader] 每日 JSON 已保存: " + path
                    + " (" + Files.size(path) + " bytes)");
        } catch (IOException e) {
            System.err.println(">>> [Downloader] 保存每日 JSON 失败（不影响入库）: " + e.getMessage());
        }
    }
}
