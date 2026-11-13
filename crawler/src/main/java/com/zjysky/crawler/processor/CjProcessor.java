package com.zjysky.crawler.processor;

import com.zjysky.crawler.SpotSource;
import com.zjysky.crawler.PriceSupport;
import com.zjysky.common.database.entity.MdSourceProduct;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.selector.Selectable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CJ 长江有色的 PageProcessor（行情历史 + 品种发现）。
 *
 * <p>品种发现：首页一个入口，按板块罗列，条目形如
 * {@code <a class="value1" href="/category/201102250293" title="长江 1#电解铜">}，
 * 板块名取同级 h2.title。实测 23 个合约。</p>
 *
 * <p>WebMagic 1.0.3 的 xsoup 对 CSS 属性前缀选择器（如 a[href^=/category/]）支持不稳，
 * 因此先用 CSS 选出 a 标签，再用正则从元素 HTML 里取 href / title。</p>
 */
public final class CjProcessor extends AbstractSpotProcessor {

    private static final String LINK_PREFIX = "/category/";
    private static final Pattern HREF_PATTERN =
            Pattern.compile("href\\s*=\\s*[\"']?([^\"'\\s>]+)");
    private static final Pattern TITLE_PATTERN =
            Pattern.compile("title\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern DIGITS = Pattern.compile("\\d{6,20}");

    public CjProcessor() {
        super(SpotSource.CJ);
    }

    /** 起始页：CJ 只有首页一个入口。 */
    @Override
    public List<Request> discoveryRequests() {
        Request request = new Request(
                com.zjysky.crawler.CrawlerConfig.str(SpotSource.CJ.cfg() + "home-url",
                        SpotSource.CJ.homeUrl()));
        request.putExtra(PriceSupport.PRODUCT_GROUP, SpotSource.CJ.name() + "现货");
        return List.of(request);
    }

    /** 首页板块 → a href="/category/{id}" title="{name}"。 */
    @Override
    protected void parseProducts(Page page, String raw, String defaultGroup) {
        Set<String> seen = new LinkedHashSet<>();
        List<MdSourceProduct> products = new ArrayList<>();

        // 1) 按板块解析，拿到更准确的分组名
        List<Selectable> blocks = page.getHtml().css("div.tab-components").nodes();
        for (Selectable block : blocks) {
            String group = textOf(block.css("h2.title"));
            collectAnchors(block, group.isBlank() ? defaultGroup : group, seen, products);
        }
        // 2) 兜底：站点改版后板块结构变化时整页扫描
        if (products.isEmpty()) {
            collectAnchors(page.getHtml(), defaultGroup, seen, products);
        }

        page.putField(PriceSupport.SOURCE_PRODUCTS, products);
    }

    private void collectAnchors(Selectable scope, String group, Set<String> seen,
                                List<MdSourceProduct> products) {
        for (Selectable a : scope.css("a").nodes()) {
            String html = a.get();
            if (html == null || html.isBlank()) {
                continue;
            }
            Matcher hm = HREF_PATTERN.matcher(html);
            if (!hm.find()) {
                continue;
            }
            String href = hm.group(1);
            if (!href.startsWith(LINK_PREFIX)) {
                continue;
            }
            String productId = href.substring(LINK_PREFIX.length());
            if (!DIGITS.matcher(productId).matches() || !seen.add(productId)) {
                continue;
            }
            String name = "";
            Matcher tm = TITLE_PATTERN.matcher(html);
            if (tm.find()) {
                name = tm.group(1).trim();
            }
            if (name.isEmpty()) {
                name = textOf(a);
            }
            if (name.isEmpty()) {
                name = productId;
            }
            products.add(newProduct(productId, name, group));
        }
    }
}
