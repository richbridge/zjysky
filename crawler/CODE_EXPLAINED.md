# crawler 模块代码解释（学习版）

> 面向「读懂并改得动这个模块」的源码讲解。所有类名、方法名、配置项均按当前仓库实际代码核对，
> 不使用伪代码。建议配合 IDE 一边看文档一边跳转源码。
>
> 工程位置：`D:\code\zjysky` ｜ 模块：`crawler/` ｜ 讲解版本：2026-11-13 重构后

---

## 0. 怎么用这份文档

建议按下面五步读，每一步都能独立形成一个完整的认知闭环：

| 步骤 | 读什么 | 读完应该能回答 |
| --- | --- | --- |
| ① | 第 1～3 章：定位、全景、WebMagic 概念 | 这个模块在整个工程里处于什么位置？WebMagic 的四个组件分别干什么？ |
| ② | 第 4～5 章：一次采集的生命周期、配置体系 | 从点下「采集」到数据进 MySQL，中间经过了哪些对象？配置从哪来？ |
| ③ | 第 6～12 章：逐包逐类讲解 | 每个类是干什么的、为什么这么设计、关键代码怎么读 |
| ④ | 第 13～15 章：数据层、admin 集成 | 8 张表怎么对应？admin 是怎么复用 crawler 的？ |
| ⑤ | 第 16～19 章：设计决策、扩展、排错、命令 | 我要加一个数据源/改一个时间/查一次故障，该动哪里？ |

**两个阅读技巧**

1. 顺着一条真实链路读，不要按字母顺序读类。最清晰的链路是 `CrawlerService.main` → `ShfeSpider.runRealtime()` → `ShfeProcessor.process()` → `ShfePipeline.process()`，它把四大组件全走了一遍，且代码最短。
2. 每个类的 Javadoc 都是设计说明，不是装饰。这个模块里大量「为什么」写在 Javadoc 里（例如为什么 `quote-mode=latest`、为什么盘中半成品不能入库），读代码前先读类注释。

---

## 1. 模块定位：它是什么，不是什么

### 1.1 工程坐标

根 `pom.xml` 现在聚合 4 个部分：`common`、`crawler`、`admin`、`web`（前端 Vite/React）。
`crawler/pom.xml` 的 `<artifactId>crawler</artifactId>` 依赖 `common`，被 `admin` 依赖：

```
web (React + Vite, 5173)
   │  /api/*  → 反向代理
   ▼
admin (Spring Boot 4, 8080)  ──依赖──▶  crawler ──依赖──▶ common ──▶ MySQL zjysky
                                          ▲
                                   纯 Java SE，无 Spring 容器
```

### 1.2 关键定位：**crawler 是纯 Java SE 模块**

这是理解全模块的第一把钥匙。`crawler/pom.xml` 里**没有** `spring-boot-starter`，也没有任何 `@Component` / `@Service` / `@Value`。它有两种运行形态：

| 形态 | 入口 | DataSource 从哪来 | 谁管连接池 | 谁触发采集 |
| --- | --- | --- | --- | --- |
| 独立运行 | `com.zjysky.crawler.CrawlerService.main` | 自己按 `application.yml` 建 HikariCP | `DbConfig` 自建 + JVM 关闭钩子 | 命令行 / `AbstractSourceScheduler` 定时线程 |
| 被 admin 引入 | 无 main 调用 | `CrawlerBridgeConfig` 注入 Spring 的 DataSource | Spring 容器 | 前端 `/api/crawl/run` 按需触发 |

因为不跑 Spring，所以有下面这些「看起来绕」的写法，全是必然结果：

- 配置不用 `@Value`，而是 `CrawlerConfig` 用 SnakeYAML 自己读 `application.yml` 并扁平化；
- MyBatis-Plus 不用 starter，而是 `DbConfig` 手工 `new MybatisConfiguration()`、`addMapper()`、`XMLMapperBuilder.parse()`；
- `common.MicaHolidays` 本身是 Spring `@Service`，不能自动注入，于是 `CloseTimeGuard` 手工 `new HolidaysApiImpl(...)` + `new StandardEnvironment()` 把日历对象造出来；
- 关闭时的资源回收靠 `SpiderRegistry.installShutdownHook()` / `CrawlerBridgeConfig` 的 `stopAll()`，不靠 `@PreDestroy`（admin 侧 `CrawlTaskService` 才用）。

### 1.3 模块「不做什么」

- **不定义实体、不定义 Mapper、不定义 XML**：全部来自 `common`（8 张表 ↔ 8 entity / 8 Mapper / 8 XML，见第 13 章）；
- **不建表、不删表、不改表结构**：只在启动时用 JDBC `DatabaseMetaData` 检查目标表的必要列是否齐全，缺列直接抛错；
- **不提供 HTTP 接口**：REST 全在 admin 的 `CrawlApiController`；
- **在 admin 里不启动定时线程**：非守护线程不受 Spring 生命周期管理，应用关了还会写已关闭的连接池。

### 1.4 四个数据源

| 源 | 全称 | 抓什么 | 原始格式 | 落库表 | 收盘口径 |
| --- | --- | --- | --- | --- | --- |
| SHFE | 上海期货交易所 | 历史日行情 `kx{yyyyMMdd}.dat` + 当日延时 `delaymarket_all.dat` | JSON 文本 | `md_price_shfe` | `QUOTE` / `REALTIME` |
| SGE | 上海黄金交易所 | 每日行情 Excel + 延时行情 HTML 表格 | xlsx 二进制 / HTML | `md_price_sge` | `QUOTE` / `REALTIME` |
| CJ | 长江有色 | 现货报价（先发现合约 id，再逐合约抓） | JSON / HTML | `md_price_cj` | `QUOTE` |
| SMM | 上海有色网 | 同上，25 个专区、260+ 合约 | JSON / HTML | `md_price_smm` | `NIGHT` |

`task_type` 是**区分口径**的核心字段：SHFE/SGE 盘中写 `REALTIME`，收盘写 `QUOTE`；CJ 与 SMM 用不同口径（`QUOTE` / `NIGHT`）避免互相覆盖，且两次采集用同一 `task_type` 幂等覆盖。

---

## 2. 全景：目录结构与「每目录 4 个源文件」的约定

```
crawler/src/main/java/com/zjysky/crawler/
├── CrawlerService.java      入口：CLI 命令分派、定时启动
├── CrawlerConfig.java       全局配置读取（扁平化 + 覆盖优先级）
├── PriceSupport.java        共用工具：脏值清洗、表结构自检、组件传值 key
├── DbConfig.java            MyBatis-Plus 纯 Java SE 手工装配
├── SpotSource.java          CJ / SMM 差异表（record + 枚举）
│
├── downloader/  ① 下载     AbstractSourceDownloader + Sge/Shfe/Smm/CjDownloader
├── processor/   ② 解析     AbstractSpotProcessor   + Sge/Shfe/Smm/CjProcessor
├── scheduler/   ③ 调度     AbstractSourceScheduler + Sge/Shfe/Smm/CjScheduler
├── pipeline/    ④ 落库     AbstractPricePipeline / AbstractSpotPipeline /
│                           CrawlMetaSupport + Sge/Shfe/Smm/CjPipeline
├── spider/      ★ 装配      SpiderRegistry / AbstractSpotSpider / CjSpider /
│                           SmmSpider / SgeSpider / ShfeSpider
└── guard/       ★ 守卫      CloseTimeGuard（交易日/收盘/定稿） + CollectGuard（当天不重复采）
crawler/src/main/resources/
├── application.yml          唯一配置入口（285 行，逐段注释）
└── db-init.sql              新环境建库/授权脚本（不建业务表）
```

**约定**：四大组件（downloader / processor / scheduler / pipeline）每个目录都必须有 `sge`、`shfe`、`smm`、`cj` 四个以源命名的文件；共性逻辑收敛到该目录的 `Abstract*` 基类。这样：

- 找「SMM 的解析逻辑」→ 直接去 `processor/SmmProcessor.java`，不用在 5000 行里搜；
- 改「所有源的下载行为」→ 只改 `AbstractSourceDownloader` 一处；
- 根包只有 5 个全局类，不再堆砌工具类。

`spider/` 和 `guard/` 不是 WebMagic 四大组件，前者是**装配与编排**（把四个组件拼起来并启动），后者是**业务规则守卫**（什么时候可以采、数据是否定稿）。

---

## 3. 必须先懂的 WebMagic 概念

WebMagic 1.0.3 把一次采集拆成四个接口。`Spider` 是它们的容器和调度器：

```
        Request(URL + extra)
              │
   ┌──────────▼───────────┐
   │ ③ Scheduler          │  保存待抓 URL、去重（这里用自带 QueueScheduler）
   └──────────┬───────────┘
              ▼
   ┌──────────────────────┐
   │ ① Downloader         │  GET 页面 → Page.rawText / Page.bytes
   └──────────┬───────────┘  （本模块额外把原始响应落盘到 data/）
              ▼
   ┌──────────────────────┐
   │ ② PageProcessor      │  process(Page)：解析，page.putField("xxxList", 数据)
   └──────────┬───────────┘  getSite()：超时/重试/UA/Referer
              ▼
   ┌──────────────────────┐
   │ ④ Pipeline           │  process(ResultItems, Task)：把 putField 的数据写库
   └──────────────────────┘
```

几个高频 API，后面读代码一直会碰到：

| API | 作用 | 本模块用法 |
| --- | --- | --- |
| `Spider.create(processor)` | 创建爬虫，参数是解析器 | 所有 Spider 的起点 |
| `.setDownloader(...)` / `.setScheduler(...)` / `.addPipeline(...)` | 替换/追加三大组件 | `ShfeSpider.create()` 里一行一个 |
| `.addUrl(url)` / `.addRequest(request)` | 投放待抓地址 | 现货需要带 extra，所以用 `addRequest` |
| `Request.putExtra(k, v)` | 给请求附加上下文 | 传 `productId`、`productName`、`startDate` 等 |
| `page.putField(k, v)` | 处理器 → 管道传数据 | `shfeList` / `sgeList` / `spotList` / `sourceProducts` |
| `resultItems.get(k)` | 管道取数据 | `resultItems.get("shfeList")` |
| `page.setSkip(true)` | 丢弃本页，不进 Pipeline | 解析失败、返回为空、收盘数据未定稿 |
| `page.setBinaryContent(true)` / `page.getBytes()` | 二进制下载 | SGE 的 xlsx |
| `Site.me()` | 站点参数 | 每个 Processor 的 `getSite()` |

**组件间传值的 key 常量集中在 `PriceSupport`：**

```java
public static final String SOURCE_PRODUCTS = "sourceProducts"; // 品种发现的合约
public static final String LINKED_PRODUCTS = "linkedProducts"; // LME/SHFE 关联合约
public static final String SPOT_ROWS      = "spotList";       // CJ/SMM 报价行
public static final String PRODUCT_ID     = "productId";
public static final String PRODUCT_NAME   = "productName";
public static final String PRODUCT_GROUP  = "productGroup";
public static final String START_DATE     = "startDate";
public static final String END_DATE       = "endDate";
```

SHFE / SGE 用的是字符串字面量 `"shfeList"` / `"sgeList"`（历史遗留，仅在各自的 Processor + Pipeline 之间成对出现）。这是目前的小不一致，学习时记住即可。

---

## 4. 一次采集的完整生命周期

### 4.1 最短链路：SHFE 当日实时

以命令行 `CrawlerService once SHFE today`（或前端点「实时采集」）为例：

1. **入口**：`CrawlerService.main` → `once(args)` → `onceShfe()` → `ShfeSpider.runRealtime()`。
2. **装配**：`ShfeSpider.create(new ShfePipeline())`：
   ```java
   return Spider.create(new ShfeProcessor())            // ② 解析
           .setDownloader(ShfeDownloader.create())      // ① 下载
           .setScheduler(new QueueScheduler())          // ③ 队列去重
           .addPipeline(pipeline)                       // ④ 落库
           .thread(CrawlerConfig.intVal(CFG + "spider.thread", 1));
   ```
   注意 `create()` 返回的是**还没开跑**的 Spider，调用方决定 `.addUrl(...)` 传哪个地址和 `.addPipeline(...)` 传落库还是丢弃。
3. **启动**：`SpiderRegistry.run(spider)` 包一层 `spider.run()`，把实例登记进活跃集合，便于应用关闭时统一 `stop()`。
4. **下载**：`ShfeSpider.urlForTodayDelay()` 拼出 `delaymarket_all.dat?params=...`；`AbstractSourceDownloader.download()` 设置 `Accept`/`Accept-Language` 头，调 `super.download()`，把文本放进 `Page.rawText`，并按 `crawler.shfe.downloader.*` 把原始 `.dat` 写到 `data/shfe/`。
5. **解析**：`ShfeProcessor.process()` 发现 URL 含 `delaymarket_all.dat` → `parseDelay()`：遍历二维数组 `delaymarket`，把 `lastprice`、`openprice` 等小写字段映射为 `MdPriceShfe`，`task_type=REALTIME`，`trade_date` 取每行 `updatetime` 前 10 位，整行原始 JSON 存进 `rawJson`；最后 `page.putField("shfeList", list)`。
6. **落库**：`ShfePipeline.process()` 从 `ResultItems` 取 `shfeList`：
   - 若是收盘口径（`QUOTE`），先用 `CloseTimeGuard.finalizedShfe()` 判断「是否已定稿」，不定稿直接 `saveMeta(失败)` 并 return；
   - `MdPriceShfeMapper.upsertBatch(list)` 批量 `INSERT ... ON DUPLICATE KEY UPDATE`；
   - 顺手把合约 id 写进 `md_source_product`；
   - `saveMeta()` 写字段字典 `md_crawl_field` + 采集日志 `md_crawl_log`（失败不影响主数据）；
   - 若是收盘口径，`overwriteRealtime()` 删除同日 `REALTIME` 行，实现「收盘覆盖实时」；
   - 打印样例行。
7. **结束**：Spider 从 `SpiderRegistry` 活跃集合移除；数据已在库里，前端 `/api/crawl/prices?source=SHFE` 能查到。

### 4.2 最长链路：CJ 全部合约（两步走）

`CjSpider.of("CJ").runAll(null, null)`：

1. **品种发现**：`discoverProducts()` → `CjProcessor.discoveryRequests()` 给出首页一个 URL → `createProductSpider()` 用 `CjDownloader.forProducts()`（`cj.product` 子配置，HTML，不落盘）+ `QueueScheduler` + 一个只写字典表的 Pipeline → 首页 `a[href^="/category/"]` 解析出约 23 个合约 → 写 `md_source_product`，并放进内存缓存（默认 10 分钟）。
2. **逐合约采集**：`PriceSupport.dedupProducts()` 去重后，对每个合约：
   - `request(p, from, to)` 生成 `https://cj.smm.cn/ajax/spot/history/{id}/{start}/{end}`，把 `productId/productName/startDate/endDate` 放进 `Request.extra`；
   - `SpiderRegistry.run(create(processor, pipeline).addRequest(request))` —— **一个合约一个 Spider，边抓边落库**；
   - `CjProcessor.parseQuote()` 按 `quote-mode=latest` 只保留响应里最新报价日的那批行；
   - `CjPipeline` 通过 `MdPriceCjMapper.upsertBatch()` 写入 `md_price_cj`；
   - `pause()` 睡 `interval-millis`（CJ 400ms / SMM 800ms）防限流。
3. 站点挂了时 `discoverProducts()` 会回退 `listFromDb()`，用库里已发现的字典继续跑。

> **为什么一个合约一个 Spider，而不是把 260 个 URL 一次性丢进一个 Spider？**
> 因为要「边抓边落库」：中途某个合约失败，前面已入库的数据不丢；同时每个合约可以独立控制间隔。代价是重复创建 Spider，量级很小可以忽略。

### 4.3 定时触发的决策链（scheduler 是大脑）

`AbstractSourceScheduler.runOnce()` 是所有定时任务共用的决策流程，伪代码：

```text
打印当前状态
if (!CloseTimeGuard.enabled())           → 手动模式：实时 + 收盘各跑一次，return
if (盘中 isIntraday)                      → 有实时动作就跑实时，否则等收盘，return
if (!shouldCollectHistory)                → 非交易日，跳过，return
tradeDate = closeDate(now)                 // 最近一个已收盘交易日
if (CollectGuard.alreadyClosed(...))       → 今天收盘数据采过了，跳过，return
closeAction.accept(tradeDate)              // 采收盘数据
CollectGuard.markClosed(...)               // 只有完整跑完才打标记，失败下次自动重试
```

`CloseTimeGuard` 把「是不是交易日 / 几点收盘」全部委托给 `common.MicaHolidays`（mica 官方 2019~2026 节假日库，含调休），保证 crawler 与 admin 口径不分叉。

---

## 5. 配置体系：`CrawlerConfig` 与 `application.yml`

### 5.1 扁平化

`CrawlerConfig.load()` 用 SnakeYAML 读 `classpath:application.yml`，`flatten()` 递归把嵌套 Map 压成 `a.b.c`：

```yaml
crawler:
  cj:
    schedule:
      open-delay-minutes: 30
```
变成 key `crawler.cj.schedule.open-delay-minutes`。业务代码统一用 `CrawlerConfig.str/intVal/boolVal/longVal/list(key, 默认值)` 取值。

### 5.2 覆盖优先级（由高到低）

```
CrawlerConfig.overlay(Map) 注入项（admin 的 Spring Environment）
        > JVM 系统属性 -Dcrawler.cj.schedule.open-delay-minutes=45
        > 环境变量 CRAWLER_CJ_SCHEDULE_OPEN_DELAY_MINUTES=45
        > application.yml 里的值
```
环境变量规则：key 转大写，`.` 和 `-` 都替换成 `_`。`resolve(key)` 里 8 行代码实现了这条链，值得读一遍。

`CrawlerConfig.effective()` 把所有生效配置合成一个 Map，`CloseTimeGuard` 用它构造 `StandardEnvironment` 灌给 `MicaHolidays` —— 这是「配置只有一份」的关键设计：节假日、收盘时间在 crawler 和 common 两侧读的是同一批 key。

`CrawlerConfig.dump()` 打印全部生效配置并自动给含 `password` 的 key 打码，对应 CLI 的 `config` 命令。

### 5.3 `application.yml` 分段速查

| 配置段 | 作用 | 典型 key |
| --- | --- | --- |
| `spring.datasource.*` | 独立运行时的 MySQL 连接与 Hikari 参数 | `url` / `username` / `hikari.maximum-pool-size` |
| `crawler.db.*` | `mapper-locations`（8 个 XML 逗号分隔）、`field-check`、`auto-create-database` | `crawler.db.field-check` |
| `crawler.meta.enabled` | 是否写字段字典 + 采集日志 | |
| `crawler.shfe.*` | 两类 URL 模板、默认日期、`history-task-type` / `today-task-type`、下载与站点参数 | `crawler.shfe.today-url` |
| `crawler.sge.*` | Excel URL 模板、默认日期；`sge.realtime.*` 是延时行情页子配置 | `crawler.sge.downloader.binary=true` |
| `crawler.cj.*` / `crawler.smm.*` | 域名、合约、日期、`quote-mode`、`date-filter`、`include-linked-ids`、`interval-millis`、`discovery.*`、`schedule.*`、`product.downloader.*`、`site.*`、`spider.*` | `crawler.cj.quote-mode=latest` |
| `crawler.market-close.*` | 时区、四源收盘时间、`overwrite-realtime`、`realtime-task-type`、`close-task-types`、节假日覆盖 | `close-times.SMM: "17:00"` |
| `crawler.schedule.*` | `enabled`、`zone`、`times.shfe` / `times.sge` | `times.shfe: "10:00,15:40"` |

**还有一类「代码支持但 yml 没显式列出」的配置**：`crawler.market-close.collect-once-per-day`、`collect-once-ttl-minutes`、`crawler.<源>.history-auto-date` 等只在代码里给了默认值（`CrawlerConfig.xxxVal(key, 默认)`）。它们仍可通过 `-D`、环境变量或 admin overlay 覆盖，只是 `CrawlerService config` 的 `dump()` 遍历的是 yml 的 `FLAT`，**不会列出这些未写进 yml 的键**——排查时别以为「config 没打印 = 没这个配置」。

**三个容易误读的点：**

1. `crawler.<源>.downloader.save-dir` 是下载落盘目录；admin 侧旧配置写过 `crawler.<源>.save-dir`，`CrawlerBridgeConfig` 做了兼容映射。
2. `history-url-template` 里的 `%s` 用 `String.format` 填；SGE 的模板有**两个** `%s`（start_date/end_date 相同）。
3. `quote-mode` 有 `latest` / `all` / `range` 三种。实测 CJ/SMM 的历史接口**忽略起止日期**，固定返回最近约 30 个交易日，所以默认 `latest`（只落响应里最新报价日那批），避免把「请求 2025 年、实际返回 2026 年」的数据当成目标日期写错。`range` 可能得到 0 条。

---

## 6. 根包 5 个类逐个讲

### 6.1 `CrawlerService` —— 入口与命令分派

职责只有三件：挂停机钩子、分派 CLI 命令、独立运行时启动定时任务。

`main` 的分派顺序（`if` 链，从上往下第一个命中即返回）：

| 第一个参数 | 行为 |
| --- | --- |
| `config` | `CrawlerConfig.dump()` 打印生效配置 |
| `market-status` | `CloseTimeGuard.printAllStatus()` 打印四源采集窗口 |
| `schedule` | `printSchedule()` 打印四源每日触发时刻 |
| `products` | `discover()` 只做品种发现并打印合约 |
| `once` | `once(args)` 采一次并落库 |
| `download` | `download(args)` 只落盘不入库 |
| `http...` | 兼容旧用法，按 SHFE 单 URL 处理 |
| `HH:mm` | `startSchedules(mode)` 用统一时刻启动四源定时 |
| 空 + `schedule.enabled=false` | `runAllScheduledOnce()` 四源各跑一次决策 |
| 其它 | `startSchedules(mode)` 按各自配置启动定时 |

`once()` 里有个兼容分支值得注意：若第二个参数是 URL 或纯日期，就退回「SHFE 单 URL / 指定日期」的老用法；否则 `source = args[1].toUpperCase()` 分派到 `onceShfe / onceSge / onceSpot`。`onceSpot()` 把 CJ/SMM 的参数习惯合并成一份逻辑，靠多态 `AbstractSpotSpider` 区分源。

`startSchedules()` 用 `Thread.currentThread().join()` 把主线程挂住（定时线程是**非守护线程**）；admin 里永远不走这里。

### 6.2 `CrawlerConfig` —— 配置读取器

见第 5 章。核心是 `load()` / `flatten()` / `resolve()` 三个方法 + 5 个类型化取值方法。`OVERRIDES` 用 `ConcurrentHashMap`，因为 admin 的 `ApplicationRunner` 会在容器启动阶段写入，而采集线程并发读。

**设计要点**：`FLAT` 是 `static final`，只在类加载时读一次 yml；所有外部覆盖都走 `OVERRIDES`，所以不需要重新解析文件。

### 6.3 `PriceSupport` —— 共用工具（447 行，最值得精读的工具类）

分四块：

1. **组件传值 key**（第 3 章表格）；
2. **脏数据清洗**：`text()` 去空白、`pick()` 从 JSONObject 取第一个非空 key（兼容源站改字段名）、`decimal()` 支持科学计数法 `1.30714E7` / 千分位 / `-` 空值、`date()` 归一成 `yyyy-MM-dd`、`percent()` 统一涨跌幅量纲、`localDate()`、`json()`；
3. **表结构自检**：`checkColumns(factory, table, required)` 用 JDBC `DatabaseMetaData.getColumns()` 读真实列，缺列抛 `IllegalStateException`；`requireSafeTable()` 用正则白名单校验表名（因为表名只能 `${}` 拼接，防注入）；
4. **CJ/SMM 通用处理**：`historyRows()` 兼容 data/list/result/rows 四种根节点字段名、`selectRows()` 实现三种 `quote-mode`、`linkedProducts()` 从 `lme_list`/`shfe_list`/`lme_data`/`shfe_data` 提取关联合约并加 `LME:`/`SHFE:` 前缀防主键冲突、`dedupProducts()` 按 productId 保序去重；
5. `isClosedDataSource()` 沿异常链找 "has been closed" 等字样，用于热重启时把「池已关闭」与业务失败区分开。

**`percent()` 为什么要区分有没有 `%`？**

```java
"1.55%"  → 1.55      // SGE Excel 给的是百分数字符串
-0.00124 → -0.124    // CJ/SMM 的 vchange_rate 是小数比例
"-0.12%" → -0.12     // *_show 展示字段兜底
```
不统一量纲，同一列里会同时出现「1.55」和「0.0124」，前端涨跌幅直接错 100 倍。

**`checkColumns()` 为什么改用 `DatabaseMetaData` 而不是查 `information_schema` SQL？**
原实现把跨表 SQL 塞在 `CrawlMetaMapper` 里，导致 Mapper XML 无法与业务表一一对应。改成 JDBC 元数据后，8 个 XML 全部只操作自己那张表，结构更干净。

### 6.4 `DbConfig` —— 纯 Java SE 的 MyBatis-Plus 装配

`build()` 的顺序：

```text
读连接参数 → （独立运行时）ensureDatabase 尽力建库 → 建 HikariDataSource + 注册 shutdown hook
→ 试连一条 Connection 打印库名
→ new Environment(id, JdbcTransactionFactory, ds)
→ new MybatisConfiguration(env) + 全局配置 + mapUnderscoreToCamelCase(true)
→ addMapper(8 个 Mapper 接口)
→ 逐个 XMLMapperBuilder.parse() 加载 8 个 XML
→ new MybatisSqlSessionFactoryBuilder().build(configuration)
```

要点：

- **双重检查锁单例**：`getSqlSessionFactory()` 用 `volatile + synchronized` 保证并发只建一次；
- **两种数据源**：`externalDataSource`（Spring 注入）优先；只有 `ownedDataSource` 会被 `close()` 关闭，避免越权关掉 Spring 的池；
- `useDataSource()` 必须在首次 `getSqlSessionFactory()` 之前调用，否则抛异常（配置已经固化，不能中途换池）；
- `toServerUrl()` / `databaseName()` 是两个纯字符串工具，把 `jdbc:mysql://host:3306/zjysky?params` 拆成「不带库名的 server URL」和「库名」，供 `CREATE DATABASE IF NOT EXISTS` 使用；账号无 CREATE 权限时异常被吞掉，只打一行提示，由后面的试连给出真正结论。

### 6.5 `SpotSource` —— 用 record 表达「差异表」

```java
public record SpotSource(String name, String baseUrl, Discovery discovery, String taskType,
                         String defaultStartDate, String defaultEndDate, String defaultProductId,
                         int discoveryThreads, long intervalMillis) { ... }
```

CJ 与 SMM 是同一平台的两个站，行情接口、JSON 字段、表结构完全一致，差别只有：域名、目录页解析方式（`HREF_CATEGORY` / `OPTION_CATEGORY`）、`task_type`、默认日期、默认合约、发现页并发、采集间隔。把这些收敛成一个 `record` + 两个常量后，`AbstractSpotProcessor` / `AbstractSpotPipeline` / `AbstractSpotSpider` 各只有**一份**实现。

关键方法：

| 方法 | 说明 |
| --- | --- |
| `of(name)` / `all()` | 按名取源，大小写不敏感 |
| `cfg()` | 返回 `crawler.cj.` 这类配置前缀 |
| `table()` | 返回 `md_price_cj` / `md_price_smm`（可配置覆盖） |
| `historyUrl(id, start, end)` | 用模板拼行情 URL |
| `homeUrl()` | 品种发现入口 |
| `newRow()` | **按源造实体**：CJ → `MdPriceCj`，SMM → `MdPriceSmm`，两者都实现 `SpotPrice` |

`newRow()` 是「一套解析代码写两张表」的关键：Processor 只面向 `SpotPrice` 接口编程，落库由各源 Pipeline 分派到自己的 Mapper。

---

## 7. `downloader/` —— ① 下载组件

### 7.1 `AbstractSourceDownloader`（238 行，唯一有实现的地方）

继承 WebMagic 的 `HttpClientDownloader`，重写 `download(Request, Task)`：

```java
boolean binary = CrawlerConfig.boolVal(cfg + "binary", false);
if (binary) request.setBinaryContent(true);        // SGE xlsx
request.addHeader("Accept", acceptHeader(binary, extension));
request.addHeader("Accept-Language", "zh-CN,zh;q=0.9");
Page page = super.download(request, task);         // 真正发请求
// 二进制 → page.getBytes()；文本 → page.getRawText()
savePayload(request.getUrl(), bytesOrNull, rawOrNull);
return page;
```

`cfg` 来自构造参数 `source`（如 `sge`、`cj.product`），拼成 `crawler.{source}.downloader.`。这就是「子配置」机制：同一个类用不同前缀读不同行为。

`acceptHeader()` 按 `binary` 与 `extension` 给三种 Accept：

- xlsx/二进制 → `application/octet-stream,application/vnd...sheet,*/*`
- json/dat → `application/json, text/plain, */*`
- 其它 → `text/html,application/xhtml+xml,*/*`

`savePayload()` 是「原始响应留档」，`skipExisting()` 判断 `skip-existing`，`targetPath()` 决定目录与文件名。`fileName()` 有四级推断，读代码时按这个顺序看：

1. URL 末段自带扩展名（如 `kx20251031.dat`、`delaymarket_all.dat`）→ 原样用；
2. query 里有 `start_date` → `sge_2025-11-10_2025-11-10.xlsx`；
3. 路径形如 `/history/{id}/{start}/{end}` → `cj_201102250293_2025-10-06_2025-11-06.json`；
4. 兜底 → `smm.product_copper.html`。

最后 `sanitize()` 把非 `[a-zA-Z0-9_.-]` 字符换成下划线，防路径穿越/非法文件名。**注意 savePayload 的 IOException 只打日志、不抛**：留档是辅助动作，不能因为磁盘满导致行情采集失败。

### 7.2 四个薄子类

| 类 | 构造默认前缀 | 额外工厂 | 用途 |
| --- | --- | --- | --- |
| `ShfeDownloader` | `crawler.shfe.downloader.` | `create()` | `.dat` 文本 |
| `SgeDownloader` | `crawler.sge.downloader.` | `create()`、`forRealtime()` = `sge.realtime` 子配置 | xlsx 二进制 / 延时 HTML |
| `CjDownloader` | `crawler.cj.downloader.` | `create()`、`forProducts()` = `cj.product` | 行情 JSON / 目录 HTML |
| `SmmDownloader` | `crawler.smm.downloader.` | `create()`、`forProducts()` = `smm.product` | 同上 |

它们每个只有 25～30 行，只做一件事：把配置前缀传给父类。**看懂这个目录的关键是理解「配置即差异」**——新增同形态的站点，加一个这样的子类 + 改 yml，不需要改下载逻辑。

---

## 8. `processor/` —— ② 解析组件

职责边界非常干净：**把 `Page` 里的原始响应解析成实体列表，`putField` 交出去；不下载、不写库、不启 Spider。**

### 8.1 `AbstractSpotProcessor`（CJ / SMM 共用，262 行）

`process(Page)` 按 URL 分派：

```java
if (url.contains("/ajax/spot/history/")) parseQuote(page, raw);
else                                     parseProducts(page, raw, defaultGroup);
```

- `parseQuote()` 是核心（约 80 行）：校验 `code` → `PriceSupport.historyRows(root)` 取数组 → 从 `Request.extra` 取 productId/name/日期 → 按 `quote-mode` 调 `selectRows()` → 每行 `SpotPrice d = source.newRow()` 逐字段映射 → `putField(SPOT_ROWS, rows)`；同时把 LME/SHFE 关联合约放进 `LINKED_PRODUCTS`。
- 映射表（学习时对照 `application.yml` 里的字段名看）：

  | 源站字段（含别名） | 实体属性 | 清洗方法 |
  | --- | --- | --- |
  | `renew_date` / `报价日期` / `date` | `quoteDate` | `PriceSupport.localDate()` |
  | `average` / `均价` / `average_show` | `avgPrice` | `decimal()` |
  | `highs` / `最高价` / `high_show` | `highPrice` | `decimal()` |
  | `low` / `最低价` / `low_show` | `lowPrice` | `decimal()` |
  | `vchange` / `涨跌额` / `change_value_show` | `changeValue` | `decimal()` |
  | `vchange_rate` / `涨跌幅` / `change_rate_show` | `changePct` | `percent()`（×100） |
  | `point_precision` / `价格精度` | `pointPrecision` | `intValue()` |
  | 整行 JSON | `rawJson` | `row.toJSONString()` |

  > `quoteDate == null` 的行直接 `continue` —— 唯一键含 `quote_date`，NULL 会让唯一约束失效。
- `parseProducts()` 是抽象方法，由子类实现；`buildSite()` 统一从 `crawler.<源>.site.*` 读超时/重试/UA/Referer，`getSite()` 返回给 WebMagic。
- 注意 `rows` 是**实例字段**且 `parseQuote()` 开头 `rows.clear()`：因为 `AbstractSpotSpider.runAll()` 对每个合约 new 一个 Processor、只跑一个请求，所以实例复用是安全的；`processor.getRows().size()` 用来统计本次落库行数。

### 8.2 `CjProcessor` / `SmmProcessor` —— 只有「品种发现」不同

| | CJ | SMM |
| --- | --- | --- |
| 发现入口 | 首页 1 个 URL | 25 个专区页（slug 可配置，实测去重后约 262 个合约） |
| HTML 形态 | `<a class="value1" href="/category/201102250293" title="长江 1#电解铜">` | `<option value="category_201102250376">1#电解铜</option>` |
| 解析手法 | `page.getHtml().css("div.tab-components")` 分板块 + 正则取 `href`/`title` | 正则 `OPTION` 匹配整页 `<option>` |
| 分组名 | 同级 `h2.title` 文本 | `GROUP_NAMES` 里 slug → 中文专区名 |
| 兜底 | 板块解析为 0 时整页扫 `a` 标签 | 无 |

> 为什么 CJ 不用 CSS 属性选择器 `a[href^=/category/]`？代码注释写了：WebMagic 1.0.3 的 xsoup 对该语法支持不稳，所以先 `css("a")` 拿元素，再用正则从元素 HTML 里抠属性。这是**框架版本限制导致的写法**，不是随手为之。

### 8.3 `ShfeProcessor`（361 行）

`process()` 用 `url.contains("delaymarket_all.dat") || root.containsKey("delaymarket")` 判断口径：

| | 历史 `kx{yyyyMMdd}.dat` | 当日 `delaymarket_all.dat` |
| --- | --- | --- |
| 顶层 | `o_curinstrument` / `o_curproduct` / `o_curmetalindex` 三个数组 | `delaymarket` 二维数组（26 组 / 约 302 条） |
| 字段 | 全大写（`PRODUCTNAME` / `CLOSEPRICE`…） | 全小写（`contractname` / `lastprice`…） |
| `task_type` | `QUOTE` | `REALTIME` |
| `trade_date` | URL 里 `kx(\d{8})`，兜底 `report_date` | 每行 `updatetime` 前 10 位 |
| `row_type` | `CONTRACT` / `PRODUCT` / `INDEX` / `TOTAL` | `CONTRACT` |
| 特殊处理 | `总计` 行的 symbol 用 `总计-合约`，避开与 `o_curproduct` 的「总计」撞唯一键；金属指数没有品种名，用 `SHFE金属指数` 当 symbol | 报文无 `closeprice`，把 `lastprice` 同时写 `lastPrice` 和 `closePrice` |

所有数值走本类私有 `dec()`：空串、`-`、科学计数法统一处理；`rawJson` 保存整行原始 JSON。**「未映射到表列的字段不丢」是这个模块贯穿始终的设计原则。**

### 8.4 `SgeProcessor`（390 行，文件格式最复杂）

同样按 URL 分派，两条链路：

1. **收盘 Excel**：`page.getBytes()` → `looksLikeExcel()` 校验文件头（`PK` = xlsx，`D0 CF 11 E0` = 老 xls）→ Apache POI `WorkbookFactory` 读第一个 Sheet → 第 0 行当表头，`DataFormatter` 把每行读成 `LinkedHashMap<表头, 值>` → 按中文表头 `pick(raw, "开盘价")` 等映射到 `MdPriceSge` → `page.putField("sgeList", list)`。日期取「日期」列，取不到用 URL 的 `end_date` 兜底。
2. **延时行情 HTML**（`/sjzx/yshqbg`）：jsoup `findQuoteTable()` 找含「合约 / 最新价」的表 → 先把表头读成 `列名 → 第几列`（**列顺序变了也不会错位**）→ 逐行取 5 个价格字段 → 交易日优先从页面标题 `2026年11月11日` 正则提取，兜底配置 / 当天。

三个坑写在类注释里，值得记：

- 成交金额可能是 `1.30714E7`，涨跌幅是 `1.55%`，`-` 表示空 → 全靠 `PriceSupport` 清洗；
- 延时表里的 `0.0` 是源站「今天没成交」的占位值，照实写 `raw_json`，不猜改；
- 表**没有** `last_price` 列，所以「最新价」映射到 `close_price`，收盘后被 Excel 真收盘价覆盖。

> 原 `SgeRealtimeProcessor` 已并入本类（按 URL 分派），这是「每目录 4 个源文件」约束下的合并结果：两个 Processor 的差异只是解析函数，没必要占两个文件。

---

## 9. `pipeline/` —— ④ 落库组件

### 9.1 两层基类

```
AbstractPricePipeline (implements Pipeline)       ← 所有源共用
├── AbstractSpotPipeline                          ← CJ/SMM 共用
│   ├── CjPipeline   → MdPriceCjMapper
│   └── SmmPipeline  → MdPriceSmmMapper
├── ShfePipeline     → MdPriceShfeMapper
└── SgePipeline      → MdPriceSgeMapper
```

**`AbstractPricePipeline` 集中四件事**（都是原独立类合并进来的）：

1. 构造时 `PriceSupport.checkColumns(factory, table, requiredColumns)` 做字段自检；
2. `writeProducts()` 把合约字典 upsert 到 `md_source_product`（原 `ProductPipeline`）；`collectProducts()` 从 `ResultItems` 汇总 `SOURCE_PRODUCTS` + `LINKED_PRODUCTS`；`fillName()` 按 id 反查名称；
3. `saveMeta()` 统一调 `CrawlMetaSupport` 写日志/字段字典；
4. `overwriteRealtime(taskType, pricedByDate)` 收盘落库后清理同日 `REALTIME`（原 `RealtimeOverwrite`），子类实现 `deleteRealtimeByDate()`。

`overwriteRealtime()` 的三道防线，是理解「收盘覆盖实时」的关键：

```text
① close-task-types 必须包含本次 taskType（QUOTE/NIGHT 才触发，采 REALTIME 不会自删）
② 只处理「该交易日确实写到了价格」的日期（pricedByDate > 0），盘中半成品不删实时行
③ 删除失败只打日志；行情主数据已写成功，不能因清理失败把整次采集判失败
```

**`AbstractSpotPipeline.process()` 的固定顺序**：

```text
写合约字典（失败不影响行情）
→ 取 spotList（为空直接 return）
→ 名称为空时用 fillName() 回填
→ 子类 upsertBatch() 批量写入
→ saveMeta()：字段字典 + 成功日志
→ 打印样例行（sample-size 默认 5）
```

`CjPipeline` / `SmmPipeline` 各 33 行，只做两件事：`super("CJ", "crawler.cj.table", "md_price_cj")` 绑定表名、`upsertBatch()` 选自己的 Mapper；`deleteRealtimeByDate()` 返回 0（现货两次采集同 `task_type` 幂等覆盖，没有独立 REALTIME 行）。

### 9.2 `ShfePipeline` / `SgePipeline` —— 多一道「定稿」判定

两者结构几乎一样，差别在定稿条件：

```java
// ShfePipeline
int priced  = count(closePrice != null);
int settled = count(settlePrice != null);
if (!CloseTimeGuard.finalizedShfe(total, priced, settled)) { 跳过落库 }

// SgePipeline（Excel 没有结算价列）
int priced = count(closePrice != null);
if (!CloseTimeGuard.finalizedByRatio(total, priced)) { 跳过落库 }
```

**为什么必须判？** SHFE 的 `kx{yyyyMMdd}.dat` 盘中也会返回 200，但实测 332 个合约里只有 4 个有收盘价、0 个有结算价。半成品写进去会污染当天价格，还会把已采到的有效实时行删掉。判定逻辑在 `CloseTimeGuard.finalizedShfe()`：`settled > 0 && priced * 2 >= total`。

落库成功后，两个 Pipeline 都会：

- 从行情行提取合约 id 写 `md_source_product`（SHFE 按 `productId` 去重，SGE 直接用 `contract`，分组固定「贵金属」）；
- `saveMeta()` 写元信息；
- 统计 `pricedByDate` → `overwriteRealtime()` 删同日 REALTIME；
- 打印按 `row_type` / 合约分组的摘要与样例。

### 9.3 `CrawlMetaSupport`（368 行）—— 元信息工具

- `saveFields()`：不反射实体，而是**反序列化每条 `raw_json`**，统计每个 key 的非空率与首个非空样例值，配合 `FIELD_SCHEMA` 把源站字段名翻译成中文标准名并标出对应表列，upsert 到 `md_crawl_field`。这样源站新增字段会自动出现在字典里，`column_name=null` 表示「还没结构化」。
- `saveLog()`：一次采集一条 `md_crawl_log`（成功/失败都记），`requests=pages=1`，`start_time` 由 `end_time - costMs` 反推（最多回推 1 小时防溢出），message 截断 500 字符。
- `buildFields()` 里有个细节：`nonEmpty * 100 / rows` 是整数除法，302 行里只有 1 行有值会算成 0%，但「0% 却有样例值」很误导，所以只要非空数 > 0 就至少记 1%。
- 所有写入都 try-catch，异常只打日志；`crawler.meta.enabled=false` 可整体关闭。**辅助数据绝不影响行情主数据**，这是本目录的第一原则。

---

## 10. `scheduler/` —— ③ 调度组件（CJ/SMM 双时段在这里）

> 注意：这里的「Scheduler」是**业务定时调度**，不是 WebMagic 的 `us.codecraft.webmagic.scheduler.Scheduler`。
> WebMagic 那个负责 URL 队列去重，本模块直接用自带的 `QueueScheduler`，没有自定义实现。

### 10.1 `AbstractSourceScheduler`（295 行）

一个实例 = 一个源的定时任务 + 一个非守护定时线程。构造参数：

```java
AbstractSourceScheduler(String source, Runnable realtimeAction, Consumer<String> closeAction)
```

- `realtimeAction`：盘中动作（可为 null）；
- `closeAction`：收盘动作，参数是交易日 `yyyy-MM-dd`；
- `triggerTimes()`：抽象方法，返回「当日分钟数」集合（`Set<Integer>`），由子类按配置算。

静态登记表 `ALL` 固定 `SHFE/SGE/CJ/SMM` 顺序，`all()` / `of(name)` 供 CLI 与 admin 使用。

**时间工具**：`parseTimes()` 解析逗号分隔的 `HH:mm`（非法值打提示后忽略，全非法退回默认）、`minutesOf()`、`format()`、`delayMinutes()`（已过则 +24h）、`zone()`。

**启动**：`start()` 为每个触发点 `scheduleAtFixedRate(fireOnce, delay, 1天, MINUTES)`；`startAt(h,m)` 是命令行统一覆盖；`stop()` 关线程池。`fireOnce = this::runOnce`。

**`runOnce()` 决策流程**见第 4.3 节。`closeDate(now)` 默认取「最近一个已收盘交易日」（委托 `MicaHolidays`），把 `crawler.<源>.history-auto-date=false` 后改用配置里的固定日期（手动补历史用）。

> `triggerTimes()` 必须是 `public`：`CrawlerService.printSchedule()` 在 `com.zjysky.crawler` 包，跨包调用 `com.zjysky.crawler.scheduler` 的方法，`protected` 会编译报「访问控制」错误——这是本次重构实际踩过的坑。

### 10.2 四个子类

| 类 | 触发时间来源 | 盘中动作 | 收盘动作 |
| --- | --- | --- | --- |
| `ShfeScheduler` | `crawler.schedule.times.shfe`（默认 `10:00,15:40`） | `ShfeSpider.runRealtime()` | `ShfeSpider.runHistory(date)` |
| `SgeScheduler` | `crawler.schedule.times.sge`（默认同上） | `SgeSpider.runRealtime()` | `SgeSpider.runDate(date)` |
| `CjScheduler` | 按开盘/收盘推算 | `CjSpider.of("CJ").runAll(null,null)` | `CjSpider.of("CJ").runAll(date,date)` |
| `SmmScheduler` | 按开盘/收盘推算 | `SmmSpider.of("SMM").runAll(null,null)` | `SmmSpider.of("SMM").runAll(date,date)` |

### 10.3 CJ / SMM 的「开盘后 30 分钟 + 收盘后」算法

`CjScheduler.triggerTimes()` 与 `SmmScheduler` 完全同构（只有配置前缀不同）：

```java
String cfg = "crawler.cj.schedule.";
Integer open = parseTime(source(), CrawlerConfig.str(cfg + "open-time", "09:00"));
int openMinutes  = (open == null ? 9*60 : open)
                 + CrawlerConfig.intVal(cfg + "open-delay-minutes", 30);
int closeMinutes = configuredCloseMinutes()               // market-close.close-times.CJ
                 + CrawlerConfig.intVal(cfg + "close-delay-minutes", 10);
return Set.of(openMinutes % 1440, closeMinutes % 1440);
```

代入默认配置：

| 源 | 开盘 + 延迟 | 收盘时间 + 延迟 | 实测触发点 |
| --- | --- | --- | --- |
| CJ | 09:00 + 30 = **09:30** | 15:00 + 10 = **15:10** | `09:30 / 15:10` |
| SMM | 09:00 + 30 = **09:30** | 17:00 + 10 = **17:10** | `09:30 / 17:10` |

两个触发点都走同一套 `runOnce()` 决策：

- **09:30** 处于盘中 → 执行 `realtimeAction`，抓当日早间报价；
- **15:10 / 17:10** 已收盘 → 执行 `closeAction` 抓定稿报价，并做 `CollectGuard` 当日幂等标记。

两次采集的 `task_type` 相同（CJ `QUOTE`、SMM `NIGHT`），靠唯一键 `(task_type, quote_date, product_id)` 做 `ON DUPLICATE KEY UPDATE`，**收盘那批幂等覆盖早间那批**，所以库里不会留两份。

`Set.of(...)` 还有个隐含好处：如果配置把开盘延迟和收盘延迟调成同一个时刻，`Set` 自动去重，一天只触发一次。

CLI 的 `schedule` 命令可以直接打印四源触发点，改完 yml 先验这个，不用等到点。

---

## 11. `spider/` —— 装配与编排

### 11.1 `SpiderRegistry`（72 行）

所有 Spider 统一经 `run(spider)` 启动，登记进 `ConcurrentHashMap.newKeySet()`，`finally` 里移除；`stopAll()` 在应用关闭时统一 `spider.stop()`；独立运行时 `installShutdownHook()` 注册 JVM 钩子（用 `AtomicBoolean` 保证只注册一次）。

**它解决的是真实事故**：WebMagic 的 `spider.run()` 会起自己的抓取线程池；Spring Boot / DevTools 热重启时，Hikari 连接池先关，后台线程还在写库 → `HikariDataSource has been closed` + 丢数据。配合 `PriceSupport.isClosedDataSource()` 识别这类异常，只打一行提示而不是刷一屏堆栈。

### 11.2 `AbstractSpotSpider`（334 行，现货总指挥）

静态 `INSTANCES = Map.of("CJ", new CjSpider(), "SMM", new SmmSpider())`，`of(name)` 取实例。

**四个抽象方法 + 一个默认实现**，把「按源选组件」交给子类：

```java
protected abstract AbstractSpotProcessor   newProcessor();
protected abstract AbstractSpotProcessor   newProductProcessor();
protected abstract AbstractSourceDownloader newDownloader();
protected abstract AbstractSourceDownloader newProductDownloader();
protected AbstractSpotPipeline newPipeline() {   // 默认按名字分派
    return "CJ".equals(source.name()) ? new CjPipeline() : new SmmPipeline();
}
```

两个装配方法：

- `create(processor, pipeline)`：行情链路 = 本源 Processor + 本源 Downloader + `QueueScheduler` + 传入的 Pipeline + `spider.thread` 线程数；
- `createProductSpider(processor)`：品种发现链路 = 本源 Processor + `forProducts()` Downloader + `QueueScheduler` + 一个只写 `md_source_product` 的 Pipeline + `spider.discovery-thread` 并发。

`discoverProducts()` 三步：查内存缓存（TTL 默认 10 分钟）→ 跑发现 Spider 并收集 → 为空则 `listFromDb()` 兜底；`discoverProducts(true)` 强制刷新。`runAll()` / `runOne()` / `runUrl()` / `downloadOne()` 是四个对外入口，`fillExtra()` 统一把上下文塞进 `Request.extra`，`pause()` 按 `interval-millis` 限流。

> **一个必须记住的 Java 规则**：子类不能定义与父类**实例方法同签名**的静态方法。原来 `CjSpider` / `SmmSpider` 里有 `public static runAll(...)` 便捷入口，与父类 `public int runAll(...)` 冲突，编译直接报错。已删除静态方法，调用方统一写 `CjSpider.of("CJ").runAll(...)`。

### 11.3 `CjSpider` / `SmmSpider` / `SgeSpider` / `ShfeSpider`

- 前两个各 47 行，只实现 4 个 `newXxx()` 工厂；
- `SgeSpider`：`create()`（xlsx 链路，`SgeDownloader.create()`）与 `createRealtime()`（HTML 链路，`forRealtime()`）共用 `SgeProcessor` 和 `SgePipeline`；`runOnce(url)` / `runDate(date)` / `runRealtime()` / `downloadOnly()` 等静态入口；`urlForPreviousWorkday()` 用 `CloseTimeGuard.previousWorkday()` 回退到最近交易日（长假后也正确）；URL 里 `start_date` 与 `end_date` 相同表示取单日，`yyyyMMdd` 自动转 `yyyy-MM-dd`（实测接口不认紧凑格式）。
- `ShfeSpider`：`create(pipeline)` 是四大组件装配的**教学范本**（第 4.1 节已逐行拆过）；`runRealtime()` = `runTodayDelay()`；`urlForHistory(date)` 把日期规范成 `yyyyMMdd` 填进 `kx%s.dat` 模板。
- 两个期货 Spider 都有 `main()`，可以单独 `java com.zjysky.crawler.spider.ShfeSpider [today|history:20251031|url]` 调试，不必走总入口。

`downloadOnly(url)` 的写法很巧妙：`create((resultItems, task) -> { })` 传一个**空 Pipeline**，于是四大组件照跑、原始文件照样落盘，只是不写库。排查「源站返回了什么」时首选这个。

---

## 12. `guard/` —— 业务规则守卫

### 12.1 `CloseTimeGuard`（219 行）

**工作日/收盘判断全部委托 `common.MicaHolidays`**，本类只做三件事：

1. `createCalendar()`：纯 Java SE 环境下手工造 Spring Bean：
   ```java
   HolidaysApiImpl api = new HolidaysApiImpl(new DefaultResourceLoader(), new HolidaysApiProperties());
   api.afterPropertiesSet();                       // 加载 2019~2026 节假日数据
   StandardEnvironment env = new StandardEnvironment();
   env.getPropertySources().addFirst(new MapPropertySource("crawler", CrawlerConfig.effective()));
   return new MicaHolidays(api, env);              // 配置只有一份，两边口径不分叉
   ```
   之后 `isTradeDate` / `isIntraday` / `shouldCollectHistory` / `closeTime` / `lastClosedTradeDate` / `status` 等全是一行委托。
2. `printAllStatus()`：`market-status` 命令的输出，含四源窗口、覆盖策略、今日是否交易日、上一工作日、节假日覆盖。
3. **收盘数据「定稿」判定**（common 没有，落库前特有）：
   - `finalizedByRatio(total, priced)`：`priced * 2 >= total`，即有收盘价的记录过半；
   - `finalizedShfe(total, priced, settled)`：还要求 `settled > 0`（结算价收盘后才公布）；
   - `describeFinalized()` 给日志用。

### 12.2 `CollectGuard`（123 行）

「收盘数据当天只采一次」的**进程内**幂等守卫：

- `DONE` 是 `ConcurrentHashMap<"源|CLOSE|交易日", 完成时刻>`；
- `alreadyClosed()` 查标记，`ttlMinutes()>0` 时超窗自动失效；
- `markClosed()` 只在 `runOnce()` 完整跑完（无异常）后调用，**失败下次触发会自动重试**；
- `rolloverIfNeeded()` 跨天清理，避免内存缓慢增长；
- 开关 `crawler.market-close.collect-once-per-day`（默认 true）、`collect-once-ttl-minutes`（默认 0 = 全天有效）。

**只拦收盘，不拦实时**：盘中价格一直在变，就是要多次采样；手动命令（`once SHFE history 20251031`）也不走这里，想重采随时可以。状态只存内存，重启后清空，最多多采一次（幂等 upsert，无害）。

---

## 13. 数据层：8 张表 ↔ 8 entity / 8 Mapper / 8 XML

crawler **不定义持久层**，全部来自 `common`。这是「一一对应」约束的结果：

| 表 | 实体 (`common/.../entity`) | Mapper | XML | 由谁写 | 唯一键 / 说明 |
| --- | --- | --- | --- | --- | --- |
| `md_price_shfe` | `MdPriceShfe` | `MdPriceShfeMapper` | 同名 .xml | `ShfePipeline` | `(task_type, trade_date, symbol)` |
| `md_price_sge` | `MdPriceSge` | `MdPriceSgeMapper` | 同名 .xml | `SgePipeline` | `(task_type, trade_date, contract)` |
| `md_price_cj` | `MdPriceCj` | `MdPriceCjMapper` | 同名 .xml | `CjPipeline` | `(task_type, quote_date, product_id)` |
| `md_price_smm` | `MdPriceSmm` | `MdPriceSmmMapper` | 同名 .xml | `SmmPipeline` | 同 CJ |
| `md_source_product` | `MdSourceProduct` | `MdSourceProductMapper` | 同名 .xml | `AbstractPricePipeline` | `(source, product_id)` 合约字典 |
| `md_crawl_field` | `MdCrawlField` | `MdCrawlFieldMapper` | 同名 .xml | `CrawlMetaSupport` | `(source, task_type, field_name)` 字段字典 |
| `md_crawl_log` | `MdCrawlLog` | `MdCrawlLogMapper` | 同名 .xml | `CrawlMetaSupport` | 流水表，只 INSERT |
| `sys_menu` | `SysMenu` | `SysMenuMapper` | 同名 .xml | admin（菜单） | 前端菜单行 |

`common/.../row/SpotPrice.java` 是**接口**，不是实体：`MdPriceCj` 与 `MdPriceSmm` 列结构完全一致，都实现它，让一套 Processor/Pipeline 能同时服务两张表。它不对应任何表，所以特意放在 `row` 包而不是 `entity` 包。

### 13.1 四类 SQL

| SQL id | 所在 XML | 作用 |
| --- | --- | --- |
| `upsertBatch` | 四个价格表 XML | `INSERT ... VALUES <foreach> ... ON DUPLICATE KEY UPDATE`（幂等覆盖，重复采集不产生重复行） |
| `deleteRealtimeByDate` | SHFE / SGE XML | `DELETE ... WHERE task_type=REALTIME AND trade_date=?` |
| `upsertProducts` / `listProducts` / `findProductName` | `MdSourceProductMapper.xml` | 合约字典 upsert / 列表 / 反查名称 |
| `upsertFields` / `listFields` / `insertLog` / `listLogs` | 两个 Meta XML | 字段字典与日志 |
| `listAll` | `SysMenuMapper.xml` | 菜单全量查询 |

所有 XML 都**固定表名**，不再有 `${tableName}` 动态拼接（旧实现把跨表 SQL 塞在 `PriceMapper` / `CrawlMetaMapper` 里，已拆掉）。表名需要参数化的地方（字段自检）改走 JDBC `DatabaseMetaData`。

### 13.2 `upsertBatch` 为什么是核心

以 `MdPriceCjMapper.xml` 为例：

```xml
<insert id="upsertBatch">
    INSERT INTO md_price_cj (task_type, product_id, product_name, quote_date, ...)
    VALUES
    <foreach collection="list" item="it" separator=",">
        (#{it.taskType}, #{it.productId}, #{it.productName}, #{it.quoteDate}, ...)
    </foreach>
    ON DUPLICATE KEY UPDATE
        product_name = VALUES(product_name), avg_price = VALUES(avg_price),
        ..., update_time = CURRENT_TIMESTAMP
</insert>
```

好处：重跑同一天、同一合约不会重复插行，而是更新已有行。这正是「CJ 09:30 早间价 → 15:10 收盘价幂等覆盖」以及「失败自动重试」能成立的基础。`create_time` 靠 DB 默认值，`update_time` 由 `CURRENT_TIMESTAMP` 维护。

---

## 14. admin 怎么复用 crawler

### 14.1 `CrawlerBridgeConfig`（启动桥梁）

`@Configuration` 里一个 `ApplicationRunner`，在容器就绪、第一个请求到达前做两件事：

```java
DbConfig.useDataSource(dataSource);   // ① 注入 Spring 的连接池（必须早于首次采集）
// ② 遍历 Environment 的 PropertySource，把 crawler.* 全部 overlay 到 CrawlerConfig
//    兼容旧 key：crawler.<源>.save-dir → crawler.<源>.downloader.save-dir
CrawlerConfig.overlay(overlay);
```

**它明确不启动定时线程**：admin 的采集完全由前端按需触发，避免非守护线程在应用关闭后继续写库。

### 14.2 `CrawlTaskService`（异步任务）

- `submit(source, mode, date)`：校验源（4 个）/ 模式（`scheduled` / `realtime` / `history`）→ `running` 集合保证**同源同时只有一个任务**（重复点击抛 `IllegalStateException`，Controller 转 409）→ 放进 2 线程的守护线程池，立即返回 `TaskInfo`，前端轮询 `/api/crawl/tasks`；
- `collect()` 分派：`scheduled` → `AbstractSourceScheduler.of(source).runOnce()`（走完整守卫决策）；`realtime` → 对应 Spider 的实时入口（CJ/SMM 的「实时」= 抓现货最新报价）；`history` → 指定日期入口；
- `@PreDestroy shutdown()` 关线程池。

### 14.3 REST 与前端

`CrawlApiController`（全部 GET，这是本工程当前约定）：

| 接口 | 作用 |
| --- | --- |
| `GET /api/crawl/status` | 四源状态（`CloseTimeGuard.status()`） |
| `GET /api/crawl/run?source=&mode=&date=` | 提交异步采集任务 |
| `GET /api/crawl/tasks?limit=` | 最近任务 + 正在跑的源 |
| `GET /api/crawl/logs?source=&limit=` | 采集日志（`MdCrawlLogMapper.listLogs`） |
| `GET /api/crawl/prices?source=&limit=` | 价格数据（`CrawlQueryService`） |

前端 `web/src/api/crawl.js` 统一封装 GET 请求（`cache: 'no-store'` 防 GET 写请求被缓存），页面 `web/src/pages/PriceCrawl.jsx` 挂在「销售管理 → 价格爬取」。

> 全工程目前把写操作也放在 GET 上（菜单增改删也是 `GET /api/menus/save?...`），不符合 REST 语义，学习时知道这是**本项目约定**即可，不要照搬到生产系统。

---

## 15. 命令速查（独立运行）

crawler 打出来的是普通 jar（无 Main-Class 清单），**不能** `java -jar crawler-1.0-SNAPSHOT.jar`。
已实测可用的独立运行方式：借用 admin 的 Spring Boot fat jar 解出的依赖目录作 classpath：

```bash
cd /d/code/zjysky
mkdir -p .cli && cd .cli
jar -xf ../admin/target/admin-0.0.1-SNAPSHOT.jar BOOT-INF/lib
java -Dfile.encoding=UTF-8 -cp "BOOT-INF/lib/*" com.zjysky.crawler.CrawlerService schedule
```

`BOOT-INF/lib/*` 里包含 crawler / common 的 jar 和全部第三方依赖，所以不需要另外拼 `target/classes`。
另一条路是用 `mvn -pl crawler dependency:build-classpath -Dmdep.outputFile=target/cp.txt` 生成依赖串后拼 classpath（离线环境需要该插件已 cached）。

> Windows 的 Git Bash 控制台默认 GBK，CLI 的中文输出会显示成乱码（不影响执行）。
> 可在命令后加 `| iconv -f GBK -t UTF-8`，或在 PowerShell 里先 `chcp 65001`。

| 命令 | 作用 |
| --- | --- |
| `config` | 打印全部生效配置（密码打码、标出被覆盖项） |
| `market-status` | 四源收盘时间 / 当前采集窗口 / 交易日 / 上一工作日 |
| `schedule` | 打印四源每日触发时刻（改完 yml 先跑这个） |
| `products CJ\|SMM` | 只做品种发现，列出合约 |
| `once SHFE [today\|realtime\|history 20251031\|scheduled\|URL]` | 采一次并落库 |
| `once SGE [realtime\|2025-11-10\|prev\|scheduled]` | 同上 |
| `once CJ\|SMM [all [起 止]\|one 合约 [起 止]\|scheduled\|URL]` | 同上 |
| `download SHFE\|SGE\|CJ\|SMM ...` | 只下载原始响应落盘，不入库 |
| `09:30`（HH:mm） | 用统一时刻启动四源定时 |
| 无参数 | 按各自配置启动四源定时并挂住主线程 |

排障时最常用的两条：`config` 看配置有没有被 admin/环境变量覆盖错，`download ...` 看源站到底返回了什么。

---

## 16. 关键设计决策与踩坑记录

按「遇到问题时能想起来」的方式整理：

| # | 决策 / 坑 | 原因与后果 |
| --- | --- | --- |
| 1 | 纯 Java SE，不用 Spring | crawler 要能被 `main` 独立跑，也要能被 Spring Boot 引入；因此配置、MyBatis、日历对象全部手工装配 |
| 2 | `CrawlerConfig.effective()` 灌给 `MicaHolidays` | 节假日/收盘时间只有一份配置，crawler 与 admin 口径不分叉 |
| 3 | `DbConfig.useDataSource()` 必须早于首次采集 | `SqlSessionFactory` 一旦建好就固化了连接池；中途换会抛异常。热重启关池问题也由此解决 |
| 4 | 只关闭自建连接池 | Spring 注入的池由容器管；关错了会出 `HikariDataSource has been closed` |
| 5 | 每目录 4 个源文件 + 共用基类 | 按源找代码一目了然，共性只改一处；`SgeRealtimeProcessor`、`SpotSpider`、`RawDownloader` 等旧类因此被合并/拆分 |
| 6 | `SpotSource.newRow()` + `SpotPrice` 接口 | 一套解析代码写两张结构相同的表；接口放 `row` 包，保持 entity 与表严格一一对应 |
| 7 | 字段自检改用 `DatabaseMetaData` | 让 Mapper XML 只操作自己那张表，消灭跨表大杂烩 Mapper |
| 8 | `raw_json` 保存整行原始数据 | 源站新增字段不会丢；字段字典直接反序列化它统计，不用改实体 |
| 9 | 所有 SQL 用 upsert | 重复采集、失败重试、早间价→收盘价覆盖，全部幂等 |
| 10 | 收盘定稿判定 | SHFE 盘中 `.dat` 也返回 200 但只有 4/332 有收盘价、0 结算价；不判会把半成品写进库并误删实时行 |
| 11 | `quote-mode=latest` | CJ/SMM 接口忽略 start/end，固定返回最近约 30 个交易日；默认只落最新报价日那批 |
| 12 | 一个合约一个 Spider | 边抓边落库 + 独立限流；中途失败不丢前面成果 |
| 13 | 子类不能有与父类实例方法同签名的静态方法 | 编译错误：`CjSpider.runAll` 与父类 `runAll` 冲突 → 已删静态入口，统一 `of("CJ").runAll(...)` |
| 14 | `triggerTimes()` 必须 public | `CrawlerService` 跨包调用，protected 会报「访问控制」错误 |
| 15 | 上游依赖版本显式锁定 | POI 5.5.1 需要新 `commons-io` / `commons-collections4`；WebMagic 传递老版本会 `NoSuchMethodError: BoundedInputStream.builder()`。jsoup 也固定 1.16.1 防被仲裁降级 |
| 16 | JDK 27 显式 `annotationProcessorPaths` | JDK 23+ javac 默认不自动跑注解处理器，不配 Lombok 会静默失效（实体没有 getter/setter） |
| 17 | `SpiderRegistry` + `isClosedDataSource()` | 应用关闭时后台线程可能还在写库；统一登记/停止 + 识别关池异常，避免刷屏和丢数据 |
| 18 | 表名 `${}` 拼接前 `requireSafeTable()` | MyBatis 的 `#{}` 不能用于表名；白名单正则防配置写错/注入 |

### 16.1 已知的「代码注释债」

读源码时会碰到几处 Javadoc 仍引用重构前的类名/用法，**实现是对的、注释滞后**，按下面理解即可：

| 位置 | 注释写的 | 实际是 |
| --- | --- | --- |
| `SgeSpider` 类注释 | `RawDownloader.create("sge")`、`SgeRealtimeProcessor` | `SgeDownloader.create()` / `forRealtime()`；解析已并入 `SgeProcessor` |
| `ShfeSpider` 类注释 | 由 `com.zjysky.crawler.SourceSchedule` 决定 | `scheduler.AbstractSourceScheduler` |
| `CollectGuard` 类注释 | 只被 `SourceSchedule#runOnce()` 调用 | 被 `AbstractSourceScheduler.runOnce()` 调用 |
| `CjSpider` / `SmmSpider` 类注释 | `CjSpider.runAll(null,null)` 静态入口 | `CjSpider.of("CJ").runAll(null,null)` |
| `application.yml` 头部 | 持久层由 `pipeline/DbConfig` 加载；落库表只写 `md_price_shfe` | `DbConfig` 在**根包**；四张价格表都写 |
| `db-init.sql` 注释 | 「SHFE 爬虫落库前置脚本」 | 实际是通用的建库/授权脚本，不建业务表 |

---

## 17. 扩展指南：常见改动该动哪里

### 17.1 改采集时间

| 想改什么 | 改哪个 key | 验证命令 |
| --- | --- | --- |
| CJ 开盘后延迟 | `crawler.cj.schedule.open-delay-minutes` | `CrawlerService schedule` |
| CJ/SMM 收盘后延迟 | `crawler.<源>.schedule.close-delay-minutes` | 同上 |
| 收盘时间本身 | `crawler.market-close.close-times.<源>`（会同时影响守卫判定） | `CrawlerService market-status` |
| SHFE/SGE 触发点 | `crawler.schedule.times.shfe / sge` | `CrawlerService schedule` |
| 全部源统一临时时刻 | 命令行传 `HH:mm`，无需改配置 | 命令行输出 |

### 17.2 新增一个数据源（例如某交易所 X）

按目录约定，最少要动 8 处：

1. `common`：加 `MdPriceX` 实体 + `MdPriceXMapper` + 同名 XML（含 `upsertBatch`）；
2. `crawler/application.yml`：加 `crawler.x.*` 配置段 + `market-close.close-times.X`；
3. `downloader/XDownloader.java`：薄子类绑定 `crawler.x.downloader.`；
4. `processor/XProcessor.java`：实现 `PageProcessor`，`page.putField("xList", list)`；
5. `pipeline/XPipeline.java`：继承 `AbstractPricePipeline`，实现 `upsertBatch` 与 `deleteRealtimeByDate`；
6. `spider/XSpider.java`：装配四个组件 + 静态入口；
7. `scheduler/XScheduler.java`：继承 `AbstractSourceScheduler`，给出 `triggerTimes()` 并登记到 `ALL`；
8. `CrawlerService`（`once`/`download` 分派）、`CrawlTaskService`（admin 的 `SOURCES` 与分派）、`CrawlerBridgeConfig.SPOT_SOURCES`（如需）、`DbConfig`（注册新 Mapper + XML）、`crawler.db.mapper-locations`。

若新源与 CJ/SMM 同平台，可以复用 `SpotSource` 思路：只加一个 record 常量 + 一个目录页解析子类，Processor/Pipeline/Spider 完全不用写。

### 17.3 给某张表加一个字段

1. 先改数据库表（本模块不建表不改表）；
2. 改 `common` 实体加属性；
3. 改对应 XML 的 `INSERT` 列与 `ON DUPLICATE KEY UPDATE`；
4. 把列名加进该 Pipeline 的 `REQUIRED` 列表（否则字段自检不会发现遗漏）；
5. 在 Processor 里映射源站字段；如想让字段字典识别它，在 `CrawlMetaSupport.FIELD_SCHEMA` 补一条 `Meta(label, column)`；
6. 跑 `once <源> ...`，看 `[Schema]` 输出与样例行。

### 17.4 改口径（`task_type`）

`task_type` 是唯一键的一部分，也是「收盘覆盖实时」的判定依据：

- 改 `crawler.<源>.task-type` / `today-task-type` / `history-task-type`；
- 若新口径属于「收盘定稿」，加进 `crawler.market-close.close-task-types`；
- 注意改口径后同一天会多一份数据（旧口径不会被自动清掉），必要时手工清理。

---

## 18. 排错手册

| 症状 | 最可能的原因 | 排查顺序 |
| --- | --- | --- |
| 启动报「classpath 下找不到 application.yml」 | 资源没打进 jar / classpath 不对 | 检查 `crawler/src/main/resources/application.yml` 是否存在 |
| 启动报「表 xxx 字段不完整，缺少 [...] 」 | 表结构变更或建表脚本没跑 | 看 `[Schema]` 输出，对照 `REQUIRED` 列表改表 |
| `Invalid bound statement (not found)` | Mapper 接口方法名与 XML id 不一致，或 XML 没在 `mapper-locations` 里 | 检查 `DbConfig` 的 8 个 `addMapper` 与 XML namespace/id |
| 采集成功但库里没数据 | `page.setSkip(true)` 被触发 | 看控制台 `>>> [xxx] 跳过/解析失败/未定稿` 提示；用 `download` 看原始响应 |
| 收盘数据一直被「跳过：收盘数据尚未定稿」 | 拿的是盘中半成品 | 用 `market-status` 确认是否过了收盘时间；`history-auto-date` 是否指错日期 |
| CJ/SMM 采到 0 行 | `quote-mode=range` 且接口忽略日期；或目录页改版导致合约字典为空 | 改回 `latest`；跑 `products CJ` 看发现数量；查 `md_source_product` |
| SGE 报「返回内容不是 Excel」 | 下载被拦/返回 HTML；`binary` 没开 | 看 `[Downloader] 二进制下载完成` 与文件头；确认 `crawler.sge.downloader.binary=true` |
| `HikariDataSource has been closed` | 应用关闭/热重启时后台 Spider 还在写 | 确认 `SpiderRegistry` 钩子 / `CrawlerBridgeConfig`；生产禁用 DevTools restart |
| 前端点采集没反应 | admin 未启动 / Vite 代理未配 / 同源已有任务 | `/api/crawl/status`；Vite `server.proxy['/api']`；`/api/crawl/tasks` 看状态 |
| 配置改了不生效 | 被 JVM 参数 / 环境变量 / admin overlay 覆盖 | 跑 `config`，看 `<= 已被覆盖为` 标注 |
| 定时任务不触发 | 独立运行时才启动定时线程；admin 里靠接口触发 | `schedule` 看触发点；确认没有误在 admin 里等定时 |
| 编译报「访问控制」或「静态/实例方法冲突」 | 跨包访问 protected；子类静态方法与父类实例方法同签名 | 见第 16 章 #13 #14 |

**通用排查套路**：`config` → `market-status` → `schedule` → `download <源>`（看原始文件）→ `once <源> one <合约>`（缩小到单请求）→ 看控制台分段日志（`[Downloader]` / 解析 / `[Schema]` / `[Pipeline]` / `[Meta]`）→ 查 `md_crawl_log` 的失败 message。

---

## 19. 术语表

| 术语 | 含义 |
| --- | --- |
| 四大组件 | WebMagic 的 Downloader / PageProcessor / Scheduler / Pipeline |
| Spider | 组装四大组件并驱动执行的容器；本模块 `spider/` 包里是各源的装配器 |
| `Page` | 一次请求的响应载体，`rawText`（文本）/ `bytes`（二进制）/ `putField`（传出数据）/ `setSkip` |
| `ResultItems` | Pipeline 收到的解析结果容器，`get(key)` 取 `putField` 的值 |
| `extra` | `Request` 上附加的上下文（合约 id、日期等），跨组件传值用 |
| `task_type` | 行情口径：`QUOTE` 收盘、`REALTIME` 盘中实时、`NIGHT` SMM 夜盘；也是唯一键一部分 |
| `row_type` | `md_price_shfe` 内部行类型：`CONTRACT` / `PRODUCT` / `INDEX` / `TOTAL` |
| upsert | `INSERT ... ON DUPLICATE KEY UPDATE`，重复采集幂等覆盖 |
| 定稿 | 收盘数据已完整（有结算价、有收盘价的记录过半），才允许入库并清理实时行 |
| 品种发现 | 先从目录页解析全部合约 id，再逐合约抓行情（CJ/SMM 特有） |
| `quote-mode` | 现货响应筛选口径：`latest` / `all` / `range` |
| 守卫（guard） | `CloseTimeGuard`（时间与定稿）+ `CollectGuard`（当日幂等） |
| overlay | admin 把 Spring Environment 的 `crawler.*` 配置注入 `CrawlerConfig` 的动作 |
| 子配置 | 用 `cj.product` / `sge.realtime` 这类前缀切换同一个 Downloader 的行为 |

---

## 附录：一条完整调用链速记（背下来就能看懂全模块）

```
前端「价格爬取」按钮
  → GET /api/crawl/run?source=CJ&mode=scheduled          [admin: CrawlApiController]
  → CrawlTaskService.submit() 提交异步任务
  → AbstractSourceScheduler.of("CJ").runOnce()           [scheduler]
  → CloseTimeGuard / CollectGuard 判定「现在该采什么」
  → CjSpider.of("CJ").runAll(...)                        [spider]
  → discoverProducts()  品种发现链路
  → 每合约：Spider.create(CjProcessor)                   [processor]
        .setDownloader(CjDownloader.create())            [downloader]
        .setScheduler(new QueueScheduler())              [WebMagic 自带]
        .addPipeline(CjPipeline)                         [pipeline]
  → CjProcessor.parseQuote() → List<SpotPrice> → putField("spotList")
  → CjPipeline → MdPriceCjMapper.upsertBatch()           [common → MySQL]
  → CrawlMetaSupport → md_crawl_field + md_crawl_log
  → /api/crawl/tasks 轮询到 SUCCESS → /api/crawl/prices 展示
```

---

*文档结束。源码以仓库当前内容为准；如重构改动了类名或配置 key，请同步更新本文件第 2、15、17、18 章。*
