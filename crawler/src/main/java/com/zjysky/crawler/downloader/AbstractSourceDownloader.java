package com.zjysky.crawler.downloader;

import com.zjysky.crawler.CrawlerConfig;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.downloader.HttpClientDownloader;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 四大组件之一：Downloader 的共用实现，四个源各有一个薄子类
 * （{@link ShfeDownloader} / {@link SgeDownloader} / {@link CjDownloader} / {@link SmmDownloader}）。
 *
 * <p>四个站点下载的东西形态不同：</p>
 * <ul>
 *   <li>SHFE 历史 / 当日：{@code .dat}，本质是 UTF-8 文本 JSON；</li>
 *   <li>SGE：{@code .xlsx}，必须按二进制下载，否则 Excel 会被当成文本读坏；</li>
 *   <li>CJ / SMM：{@code /ajax/spot/history/...} 返回 JSON；</li>
 *   <li>CJ / SMM 的品种发现页：返回 HTML，默认不必落盘。</li>
 * </ul>
 *
 * <p>本类用同一个实现覆盖以上四种情况，区别只来自 application.yml：
 * {@code crawler.{源}.downloader.binary / extension / save-dir / skip-existing}。
 * 其中 {@code {源}} 可以是 {@code shfe}、{@code sge}、{@code cj}、
 * {@code smm}，也可以是 {@code cj.product}、{@code smm.product} 这种子配置。</p>
 *
 * <p>下载后做两件事：</p>
 * <ol>
 *   <li>把内容按原始形态放进 {@code Page}（文本放 rawText，二进制放 bytes）；</li>
 *   <li>把原始响应按可读文件名保存到磁盘，便于回溯和离线复盘。</li>
 * </ol>
 */
public abstract class AbstractSourceDownloader extends HttpClientDownloader {

    /** 配置前缀，如 crawler.sge.downloader. */
    private final String cfg;
    /** 配置里的短名，如 sge / cj.product，用于生成文件名。 */
    private final String source;

    protected AbstractSourceDownloader(String source) {
        this.source = source == null ? "raw" : source;
        this.cfg = "crawler." + this.source + ".downloader.";
    }

    /** 配置里的短名（sge / cj.product 等），子类工厂也会用到。 */
    protected final String configKey() {
        return source;
    }

    @Override
    public Page download(Request request, Task task) {
        boolean binary = CrawlerConfig.boolVal(cfg + "binary", false);
        if (binary) {
            // WebMagic 的二进制开关：告诉 HttpClientDownloader 把响应当 byte[] 保存
            request.setBinaryContent(true);
        }
        request.addHeader("Accept", acceptHeader(binary, CrawlerConfig.str(cfg + "extension", "")));
        request.addHeader("Accept-Language", "zh-CN,zh;q=0.9");

        Page page = super.download(request, task);
        if (page == null) {
            System.err.println(">>> [Downloader] 下载失败（Page 为 null）: " + request.getUrl());
            return null;
        }
        if (binary) {
            byte[] bytes = page.getBytes();
            if (bytes == null || bytes.length == 0) {
                System.err.println(">>> [Downloader] 二进制内容为空: " + request.getUrl());
                return page;
            }
            System.out.println(">>> [Downloader] 二进制下载完成: " + request.getUrl()
                    + " (" + bytes.length + " bytes)");
            savePayload(request.getUrl(), bytes, null);
        } else {
            String raw = page.getRawText();
            if (raw == null || raw.isBlank()) {
                System.err.println(">>> [Downloader] 文本下载失败或为空: " + request.getUrl());
                return page;
            }
            System.out.println(">>> [Downloader] 文本下载完成: " + request.getUrl()
                    + " (" + raw.length() + " 字符)");
            savePayload(request.getUrl(), null, raw);
        }
        return page;
    }

    private static String acceptHeader(boolean binary, String extension) {
        if (binary || "xlsx".equalsIgnoreCase(extension)) {
            return "application/octet-stream,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,*/*";
        }
        if ("json".equalsIgnoreCase(extension) || "dat".equalsIgnoreCase(extension)) {
            return "application/json, text/plain, */*";
        }
        return "text/html,application/xhtml+xml,*/*";
    }

    // ================================================================
    // 原始响应落盘（文本 / 二进制共用一个方法，只是写入方式不同）
    // ================================================================

    /**
     * 把下载到的内容按可读文件名保存到磁盘。
     *
     * @param url   请求地址，用于推断文件名
     * @param bytes 二进制内容（xlsx 等）；传 null 表示按文本处理
     * @param raw   文本内容；bytes 非 null 时忽略
     */
    private void savePayload(String url, byte[] bytes, String raw) {
        if (!CrawlerConfig.boolVal(cfg + "save-enabled", true)) {
            return;
        }
        try {
            Path path = targetPath(url);
            if (skipExisting(path)) {
                return;
            }
            Files.createDirectories(path.getParent());
            if (bytes != null) {
                Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                Files.writeString(path, raw, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            System.out.println(">>> [Downloader] 原始响应已保存: " + path
                    + " (" + Files.size(path) + " bytes)");
        } catch (IOException e) {
            System.err.println(">>> [Downloader] 保存原始响应失败（不影响入库）: " + e.getMessage());
        }
    }

    private boolean skipExisting(Path path) throws IOException {
        if (CrawlerConfig.boolVal(cfg + "skip-existing", true)
                && Files.exists(path) && Files.size(path) > 0) {
            System.out.println(">>> [Downloader] 原始文件已存在，跳过保存: " + path);
            return true;
        }
        return false;
    }

    private Path targetPath(String url) {
        String defaultExt = CrawlerConfig.boolVal(cfg + "binary", false) ? "bin" : "html";
        String extension = CrawlerConfig.str(cfg + "extension", defaultExt);
        String dir = CrawlerConfig.str(cfg + "save-dir", "data/" + source.replace('.', '/'));
        return Paths.get(dir).toAbsolutePath().normalize().resolve(fileName(url, extension));
    }

    /**
     * 给原始响应起一个「看文件名就知道是哪天哪个合约」的名字。
     *
     * <p>优先保留 URL 末尾已有的文件名（如 kx20251031.dat / delaymarket_all.dat）；
     * 否则按以下顺序推断：</p>
     * <ol>
     *   <li>SGE 这种带 start_date/end_date 查询参数的 → {@code sge_2025-11-10_2025-11-10.xlsx}</li>
     *   <li>CJ / SMM 这种 {@code /history/{id}/{start}/{end}} 的 → {@code cj_201102250293_2025-10-06_2025-11-06.json}</li>
     *   <li>其它（专区发现页）→ {@code smm.product_copper.html}</li>
     * </ol>
     */
    private String fileName(String url, String extension) {
        String ext = extension.startsWith(".") ? extension.substring(1) : extension;
        try {
            URI uri = URI.create(url);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String last = path.substring(path.lastIndexOf('/') + 1);

            // 1) URL 本身已有文件名，且确实带扩展名
            if (last.contains(".") && last.indexOf('.') > 0) {
                return sanitize(last);
            }

            // 2) 查询参数里带起止日期（SGE）
            String start = queryParam(uri.getQuery(), "start_date");
            String end = queryParam(uri.getQuery(), "end_date");
            if (start != null) {
                return sanitize(prefix() + "_" + start + "_" + (end == null ? start : end)
                        + "." + ext);
            }

            // 3) /ajax/spot/history/{productId}/{start}/{end}（CJ / SMM）
            List<String> parts = new ArrayList<>();
            for (String p : path.split("/")) {
                if (!p.isBlank()) {
                    parts.add(p);
                }
            }
            if (parts.size() >= 3) {
                int n = parts.size();
                String productId = parts.get(n - 3);
                String from = parts.get(n - 2);
                String to = parts.get(n - 1);
                if (from.matches("\\d{4}-?\\d{2}-?\\d{2}") && to.matches("\\d{4}-?\\d{2}-?\\d{2}")) {
                    return sanitize(prefix() + "_" + productId + "_" + from + "_" + to + "." + ext);
                }
            }

            // 4) 兜底：专区名 / 最后一段路径
            String tail = last.isBlank() ? "index" : last;
            return sanitize(prefix() + "_" + tail + "." + ext);
        } catch (Exception e) {
            return sanitize(prefix() + "_" + System.currentTimeMillis() + "." + ext);
        }
    }

    private String prefix() {
        return source.replace('.', '_').toLowerCase(Locale.ROOT);
    }

    private static String queryParam(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && name.equals(kv[0])) {
                try {
                    return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_.\\-]", "_");
    }
}
