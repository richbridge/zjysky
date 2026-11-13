package com.zjysky.crawler.processor;

import com.zjysky.crawler.CrawlerConfig;
import com.zjysky.crawler.SpotSource;
import com.zjysky.crawler.PriceSupport;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SMM 上海有色网的 PageProcessor（行情历史 + 品种发现）。
 *
 * <p>品种发现：合约分散在 25 个专区页，条目形如
 * {@code <option value="category_201102250376">1#电解铜</option>}，
 * 专区名即分组。实测去重后约 262 个合约。</p>
 */
public final class SmmProcessor extends AbstractSpotProcessor {

    /** SMM 专区 slug → 中文名（只用于 product_group 备注，不影响抓取）。 */
    private static final Map<String, String> GROUP_NAMES = new LinkedHashMap<>();

    private static final List<String> DEFAULT_SLUGS = List.of(
            "copper", "aluminum", "lead", "zinc", "nickel", "tin",
            "magnesium", "silicon", "titanium",
            "stainless-steel", "chromium", "manganese", "tungsten",
            "precious-metals", "rare-earth", "antimony", "in-ge-ga",
            "bi-se-te", "other-minor-metals",
            "metal-scraps", "photovoltaic", "semiconductor", "wirecable", "huanan");

    private static final Pattern OPTION = Pattern.compile(
            "<option\\s+value=[\"']category_(\\d+)[\"'][^>]*>([^<]+)</option>",
            Pattern.CASE_INSENSITIVE);

    static {
        GROUP_NAMES.put("copper", "铜专区");
        GROUP_NAMES.put("aluminum", "铝专区");
        GROUP_NAMES.put("lead", "铅专区");
        GROUP_NAMES.put("zinc", "锌专区");
        GROUP_NAMES.put("nickel", "镍专区");
        GROUP_NAMES.put("tin", "锡专区");
        GROUP_NAMES.put("magnesium", "镁专区");
        GROUP_NAMES.put("silicon", "工业硅专区");
        GROUP_NAMES.put("titanium", "钛专区");
        GROUP_NAMES.put("stainless-steel", "不锈钢专区");
        GROUP_NAMES.put("chromium", "铬专区");
        GROUP_NAMES.put("manganese", "锰专区");
        GROUP_NAMES.put("tungsten", "钨专区");
        GROUP_NAMES.put("precious-metals", "贵金属专区");
        GROUP_NAMES.put("rare-earth", "稀土专区");
        GROUP_NAMES.put("antimony", "锑专区");
        GROUP_NAMES.put("in-ge-ga", "铟锗镓专区");
        GROUP_NAMES.put("bi-se-te", "铋硒碲专区");
        GROUP_NAMES.put("other-minor-metals", "其他小金属专区");
        GROUP_NAMES.put("metal-scraps", "废料专区");
        GROUP_NAMES.put("photovoltaic", "光伏专区");
        GROUP_NAMES.put("semiconductor", "半导体专区");
        GROUP_NAMES.put("wirecable", "线缆专区");
        GROUP_NAMES.put("huanan", "华南专区");
    }

    public SmmProcessor() {
        super(SpotSource.SMM);
    }

    /** 当前生效的专区 URL → 专区中文名（可用 crawler.smm.category-slugs 覆盖）。 */
    private Map<String, String> urlGroups() {
        Map<String, String> out = new LinkedHashMap<>();
        String base = CrawlerConfig.str(SpotSource.SMM.cfg() + "base-url", SpotSource.SMM.baseUrl());
        List<String> slugs = CrawlerConfig.list(SpotSource.SMM.cfg() + "category-slugs", DEFAULT_SLUGS);
        for (String slug : slugs) {
            out.put(base + "/" + slug, GROUP_NAMES.getOrDefault(slug, slug));
        }
        return out;
    }

    /** 起始页：SMM 的 25 个专区页，每页一个 Request，专区名放进 extra。 */
    @Override
    public List<Request> discoveryRequests() {
        List<Request> requests = new ArrayList<>();
        urlGroups().forEach((url, group) -> {
            Request request = new Request(url);
            request.putExtra(PriceSupport.PRODUCT_GROUP, group);
            requests.add(request);
        });
        return requests;
    }

    /** 专区页下拉框 → option value="category_{id}"。 */
    @Override
    protected void parseProducts(Page page, String raw, String group) {
        List<com.zjysky.common.database.entity.MdSourceProduct> products = new ArrayList<>();
        Matcher m = OPTION.matcher(raw);
        while (m.find()) {
            String productId = m.group(1);
            String productName = cleanText(m.group(2));
            if (productId.isEmpty() || productName.isEmpty()) {
                continue;
            }
            products.add(newProduct(productId, productName, group));
        }
        System.out.println(">>> [SMM 品种发现] 共 " + products.size()
                + " 个合约，页面 " + raw.length() + " 字符，URL=" + page.getUrl());
        page.putField(PriceSupport.SOURCE_PRODUCTS, products);
    }
}
