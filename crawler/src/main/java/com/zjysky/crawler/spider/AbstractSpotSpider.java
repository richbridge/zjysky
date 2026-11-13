package com.zjysky.crawler.spider;

import com.zjysky.crawler.CrawlerConfig;
import com.zjysky.crawler.DbConfig;
import com.zjysky.crawler.PriceSupport;
import com.zjysky.crawler.SpotSource;
import com.zjysky.crawler.downloader.AbstractSourceDownloader;
import com.zjysky.crawler.downloader.CjDownloader;
import com.zjysky.crawler.downloader.SmmDownloader;
import com.zjysky.crawler.pipeline.AbstractPricePipeline;
import com.zjysky.crawler.pipeline.AbstractSpotPipeline;
import com.zjysky.crawler.pipeline.CjPipeline;
import com.zjysky.crawler.pipeline.SmmPipeline;
import com.zjysky.crawler.processor.AbstractSpotProcessor;
import com.zjysky.crawler.processor.CjProcessor;
import com.zjysky.crawler.processor.SmmProcessor;
import com.zjysky.common.database.entity.MdSourceProduct;
import com.zjysky.common.database.mapper.MdSourceProductMapper;
import org.apache.ibatis.session.SqlSession;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.ResultItems;
import us.codecraft.webmagic.Spider;
import us.codecraft.webmagic.scheduler.QueueScheduler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 现货站点爬虫（CJ 长江有色 / SMM 上海有色网）的总指挥，一个源一个子类
 * （{@link CjSpider} / {@link SmmSpider}）。
 *
 * <p>两个站同属 smm.cn 平台，接口与字段一致，所以只有一份装配实现；
 * 差异全部来自 {@link SpotSource}（域名、表名、task_type、目录页形态）。</p>
 *
 * <p>分两步走：</p>
 * <ol>
 *   <li><b>品种发现</b>：解析目录页，得到全部合约 id + 名称，写入 {@code md_source_product}；</li>
 *   <li><b>逐合约抓历史</b>：对每个 product_id 请求
 *       {@code /ajax/spot/history/{id}/{start}/{end}}，解析后立刻落库，
 *       不等全部抓完，避免中途失败前功尽弃。</li>
 * </ol>
 *
 * <p>四大组件的分工：</p>
 * <pre>
 *   CjProcessor / SmmProcessor            ② 解析（目录页 / 行情 JSON）
 *   CjDownloader / SmmDownloader          ① GET 下载 + 原始响应落盘 data/{源}
 *   QueueScheduler                        ③ URL 队列 + 去重（WebMagic 自带）
 *   CjPipeline / SmmPipeline              ④ 写 md_source_product / md_price_{源}
 * </pre>
 */
public abstract class AbstractSpotSpider {

    private static final Pattern URL_PARTS = Pattern.compile(
            "/ajax/spot/history/([^/]+)/([^/]+)/([^/]+)");

    /** 实例登记表：CJ / SMM 各一个（子类构造只绑定自己的 SpotSource）。 */
    private static final Map<String, AbstractSpotSpider> INSTANCES = Map.of(
            "CJ", new CjSpider(),
            "SMM", new SmmSpider());

    /** 取某个源的爬虫实例，源名大小写不敏感。 */
    public static AbstractSpotSpider of(String sourceName) {
        SpotSource s = SpotSource.of(sourceName);
        AbstractSpotSpider spider = INSTANCES.get(s.name());
        if (spider == null) {
            throw new IllegalArgumentException("未知现货源 " + sourceName + "，可用 CJ / SMM");
        }
        return spider;
    }

    /** 全部现货源实例。 */
    public static List<AbstractSpotSpider> all() {
        return List.copyOf(INSTANCES.values());
    }

    protected final SpotSource source;

    /** 10 分钟内重复调用直接复用已发现的合约列表，避免反复打目录页。 */
    private volatile List<MdSourceProduct> cache;
    private volatile long cacheAt;

    protected AbstractSpotSpider(SpotSource source) {
        this.source = source;
    }

    /** 本源名（大写），如 CJ。 */
    public String name() {
        return source.name();
    }

    // ================================================================
    // 子类差异点：四个组件的实例化
    // ================================================================

    /** 行情 Processor（本源）。 */
    protected abstract AbstractSpotProcessor newProcessor();

    /** 品种发现 Processor（本源）。 */
    protected abstract AbstractSpotProcessor newProductProcessor();

    /** 行情 Downloader（本源）。 */
    protected abstract AbstractSourceDownloader newDownloader();

    /** 品种发现 Downloader（本源，product 子配置）。 */
    protected abstract AbstractSourceDownloader newProductDownloader();

    /** 落库 Pipeline（本源）。 */
    protected AbstractSpotPipeline newPipeline() {
        return "CJ".equals(source.name()) ? new CjPipeline() : new SmmPipeline();
    }

    // ================================================================
    // Spider 装配
    // ================================================================

    private Spider create(AbstractSpotProcessor processor, us.codecraft.webmagic.pipeline.Pipeline pipeline) {
        return Spider.create(processor)
                .setDownloader(newDownloader())
                .setScheduler(new QueueScheduler())
                .addPipeline(pipeline)
                .thread(CrawlerConfig.intVal(source.cfg() + "spider.thread", 1));
    }

    /** 装配「品种发现」链路（HTML，默认不落盘）。 */
    private Spider createProductSpider(AbstractSpotProcessor processor) {
        return Spider.create(processor)
                .setDownloader(newProductDownloader())
                .setScheduler(new QueueScheduler())
                .addPipeline((resultItems, task) ->
                        AbstractPricePipeline.writeProducts(DbConfig.getSqlSessionFactory(),
                                AbstractPricePipeline.collectProducts(resultItems)))
                .thread(CrawlerConfig.intVal(source.cfg() + "spider.discovery-thread",
                        source.discoveryThreads()));
    }

    // ================================================================
    // 1. 发现全部合约 id
    // ================================================================

    /** 强制重新发现（忽略缓存），供 admin 的「刷新品种字典」使用。 */
    public List<MdSourceProduct> discoverProducts(boolean force) {
        if (force) {
            cache = null;
            cacheAt = 0L;
        }
        return discoverProducts();
    }

    /** 发现并落库全部合约 id；带短时缓存，站点挂了就回退读 DB 里已发现的字典。 */
    public synchronized List<MdSourceProduct> discoverProducts() {
        long ttl = CrawlerConfig.longVal(source.cfg() + "discovery.cache-minutes", 10) * 60_000L;
        long now = System.currentTimeMillis();
        List<MdSourceProduct> cached = cache;
        if (cached != null && !cached.isEmpty() && (now - cacheAt) < ttl) {
            return cached;
        }
        if (!CrawlerConfig.boolVal(source.cfg() + "discovery.enabled", true)) {
            return listFromDb();
        }

        List<MdSourceProduct> found = new ArrayList<>();
        AbstractSpotProcessor processor = newProductProcessor();
        Spider spider = createProductSpider(processor)
                .addRequest(processor.discoveryRequests().toArray(new Request[0]))
                .addPipeline((resultItems, task) -> collectProducts(resultItems, found));
        SpiderRegistry.run(spider);

        if (found.isEmpty()) {
            System.err.println(">>> [" + source.name()
                    + "] 目录页未解析到合约，改用 md_source_product 中已发现的字典");
            return listFromDb();
        }
        System.out.println(">>> [" + source.name() + "] 合约发现完成，共 "
                + found.size() + " 个 product_id");
        cache = List.copyOf(found);
        cacheAt = now;
        return cache;
    }

    /** 从 md_source_product 读取已发现的合约，作为联网发现失败时的兜底。 */
    public List<MdSourceProduct> listFromDb() {
        try (SqlSession session = DbConfig.getSqlSessionFactory().openSession(true)) {
            List<MdSourceProduct> list = session.getMapper(MdSourceProductMapper.class)
                    .listProducts(source.name());
            System.out.println(">>> [" + source.name() + "] 从 md_source_product 读取 "
                    + list.size() + " 个已发现合约");
            return list;
        } catch (Exception e) {
            throw new IllegalStateException("读取 md_source_product 失败: " + e.getMessage(), e);
        }
    }

    private static void collectProducts(ResultItems items, List<MdSourceProduct> out) {
        Object value = items.get(PriceSupport.SOURCE_PRODUCTS);
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof MdSourceProduct p) {
                    out.add(p);
                }
            }
        }
    }

    // ================================================================
    // 2. 逐合约抓历史行情
    // ================================================================

    /**
     * 抓全部合约，边抓边落库。
     *
     * @return 本次合计落库的行情行数（按响应筛选后的口径）
     */
    public int runAll(String startDate, String endDate) {
        String from = dateOrDefault(startDate, "default-start-date", source.defaultStartDate());
        String to = dateOrDefault(endDate, "default-end-date", source.defaultEndDate());
        List<MdSourceProduct> products = PriceSupport.dedupProducts(discoverProducts());
        if (products.isEmpty()) {
            throw new IllegalStateException(source.name() + " 合约字典为空，无法采集全部合约");
        }

        AbstractSpotPipeline pipeline = newPipeline();
        int total = 0;
        for (int i = 0; i < products.size(); i++) {
            MdSourceProduct p = products.get(i);
            System.out.println(">>> [" + source.name() + "] (" + (i + 1) + "/" + products.size() + ") "
                    + p.getProductId() + " " + p.getProductName());
            AbstractSpotProcessor processor = newProcessor();
            SpiderRegistry.run(create(processor, pipeline).addRequest(request(p, from, to)));
            total += processor.getRows().size();
            pause();
        }
        System.out.println(">>> [" + source.name() + "] 全部合约采集完成: " + products.size()
                + " 个合约，共 " + total + " 行落库请求已提交");
        return total;
    }

    /** 只抓一个合约（手动调试用）。 */
    public int runOne(String productId, String startDate, String endDate) {
        String id = productId == null || productId.isBlank()
                ? source.defaultProductId() : productId.trim();
        String from = dateOrDefault(startDate, "default-start-date", source.defaultStartDate());
        String to = dateOrDefault(endDate, "default-end-date", source.defaultEndDate());
        AbstractSpotProcessor processor = newProcessor();
        SpiderRegistry.run(create(processor, newPipeline())
                .addRequest(request(id, null, from, to)));
        return processor.getRows().size();
    }

    /** 只下载一个合约的原始 JSON，不入库。 */
    public void downloadOne(String productId, String startDate, String endDate) {
        String id = productId == null || productId.isBlank()
                ? source.defaultProductId() : productId.trim();
        String from = dateOrDefault(startDate, "default-start-date", source.defaultStartDate());
        String to = dateOrDefault(endDate, "default-end-date", source.defaultEndDate());
        SpiderRegistry.run(create(newProcessor(), (resultItems, task) -> { })
                .addRequest(request(id, null, from, to)));
    }

    /** 直接传完整 history URL 时使用。 */
    public int runUrl(String url) {
        Matcher m = URL_PARTS.matcher(url);
        if (!m.find()) {
            throw new IllegalArgumentException("无法从 URL 解析 productId/start/end: " + url);
        }
        String from = dateOrDefault(m.group(2), "", "");
        String to = dateOrDefault(m.group(3), "", "");
        AbstractSpotProcessor processor = newProcessor();
        Request request = new Request(url);
        fillExtra(request, m.group(1), null, from, to);
        SpiderRegistry.run(create(processor, newPipeline()).addRequest(request));
        return processor.getRows().size();
    }

    /** 按配置拼历史行情 URL。 */
    public String urlFor(String productId, String startDate, String endDate) {
        return source.historyUrl(productId, startDate, endDate);
    }

    /** 本源默认的首页地址，供 admin 展示任务 URL。 */
    public String homeUrl() {
        return source.homeUrl();
    }

    // ================================================================
    // 小工具
    // ================================================================

    private Request request(MdSourceProduct p, String from, String to) {
        return request(p.getProductId(), p.getProductName(), from, to);
    }

    private Request request(String productId, String productName, String from, String to) {
        Request request = new Request(urlFor(productId, from, to));
        fillExtra(request, productId, productName, from, to);
        return request;
    }

    private static void fillExtra(Request request, String productId, String productName,
                                  String from, String to) {
        request.putExtra(PriceSupport.PRODUCT_ID, productId);
        if (productName != null) {
            request.putExtra(PriceSupport.PRODUCT_NAME, productName);
        }
        request.putExtra(PriceSupport.START_DATE, from);
        request.putExtra(PriceSupport.END_DATE, to);
    }

    private String dateOrDefault(String value, String configSuffix, String fallback) {
        String normalized = PriceSupport.date(value);
        if (normalized != null) {
            return normalized;
        }
        if (configSuffix.isEmpty()) {
            return fallback;
        }
        return CrawlerConfig.str(source.cfg() + configSuffix, fallback);
    }

    private void pause() {
        long millis = CrawlerConfig.longVal(source.cfg() + "interval-millis", source.intervalMillis());
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
