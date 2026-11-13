import { useEffect, useRef, useState } from 'react';
import { crawlApi } from '../api/crawl';
import './PriceCrawl.css';

/** 四大源与后端 SourceSchedule 登记表保持一致。 */
const SOURCES = ['SHFE', 'SGE', 'CJ', 'SMM'];
const SOURCE_LABEL = {
  SHFE: '上期所 · 期货日行情',
  SGE: '上金所 · 现货日行情',
  CJ: '长江现货 · 报价',
  SMM: '上海有色网 · 报价',
};
const MODE_LABEL = {
  scheduled: '按调度决策',
  realtime: '实时行情',
  history: '指定日期收盘',
};

/** 四张价格表字段不同，按源挑出最有信息量的列。 */
const PRICE_COLUMNS = {
  CJ: [
    ['quoteDate', '日期'], ['productName', '品名'], ['avgPrice', '均价'],
    ['highPrice', '最高'], ['lowPrice', '最低'], ['changePct', '涨跌%'], ['taskType', '口径'],
  ],
  SMM: [
    ['quoteDate', '日期'], ['productName', '品名'], ['avgPrice', '均价'],
    ['highPrice', '最高'], ['lowPrice', '最低'], ['changePct', '涨跌%'], ['taskType', '口径'],
  ],
  SGE: [
    ['tradeDate', '日期'], ['contract', '合约'], ['openPrice', '开盘'],
    ['highPrice', '最高'], ['lowPrice', '最低'], ['closePrice', '收盘'],
    ['settlePrice', '结算'], ['volume', '成交量'], ['taskType', '口径'],
  ],
  SHFE: [
    ['tradeDate', '日期'], ['symbol', '合约'], ['productName', '品名'],
    ['openPrice', '开盘'], ['highPrice', '最高'], ['lowPrice', '最低'],
    ['closePrice', '收盘'], ['settlePrice', '结算'], ['volume', '成交量'], ['taskType', '口径'],
  ],
};

const TASK_COLUMNS = [
  ['source', '源'], ['mode', '方式'], ['date', '日期'],
  ['status', '状态'], ['costMs', '耗时(ms)'], ['message', '说明'],
];
const LOG_COLUMNS = [
  ['startTime', '开始时间'], ['source', '源'], ['taskType', '口径'],
  ['statusText', '结果'], ['records', '行数'], ['costMs', '耗时(ms)'], ['message', '说明'],
];

function today() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function cell(value) {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (Array.isArray(value)) return value.join(' / ');
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function statusTag(value) {
  const text = String(value || '').toUpperCase();
  const kind = text === 'SUCCESS' ? 'ok' : text === 'FAILED' ? 'bad' : 'run';
  const label = text === 'SUCCESS' ? '完成' : text === 'FAILED' ? '失败' : '进行中';
  return <span className={`crawl-tag crawl-tag-${kind}`}>{label}</span>;
}

function DataTable({ columns, rows, empty = '暂无数据', renders = {} }) {
  if (!rows || rows.length === 0) return <div className="crawl-empty">{empty}</div>;
  return (
    <div className="crawl-table-wrap">
      <table className="crawl-table">
        <thead>
          <tr>{columns.map(([key, label]) => <th key={key}>{label}</th>)}</tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={row.id ?? index}>
              {columns.map(([key]) => {
                const raw = row[key];
                const text = typeof raw === 'string' ? raw : cell(raw);
                return (
                  <td key={key} title={text}>
                    {renders[key] ? renders[key](raw) : cell(raw)}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * 价格爬取（销售管理 → 价格爬取）。
 *
 * 页面只负责展示与触发，采集 / 交易日判断 / 落库全在 admin + crawler 里完成：
 * 状态与数据来自 REST，采集是异步任务，任务跑完会自动刷新日志和价格表。
 */
export default function PriceCrawlPage() {
  const [statusList, setStatusList] = useState([]);
  const [tasks, setTasks] = useState({ running: [], items: [] });
  const [logs, setLogs] = useState([]);
  const [prices, setPrices] = useState([]);

  const [source, setSource] = useState('CJ');
  const [mode, setMode] = useState('scheduled');
  const [date, setDate] = useState(today());
  const [querySource, setQuerySource] = useState('CJ');
  const [queryDate, setQueryDate] = useState(today());

  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null);
  const [error, setError] = useState('');
  const queryRef = useRef({ source: 'CJ', date: today() });
  const prevRunning = useRef(0);

  async function refreshStatus() {
    try {
      setStatusList(await crawlApi.status());
    } catch (e) {
      setError(e.message);
    }
  }

  async function refreshTasks() {
    return crawlApi.tasks();
  }

  async function refreshLogs() {
    try {
      setLogs(await crawlApi.logs('', 20));
    } catch {
      // 顶部错误条统一提示，日志区保持空状态
    }
  }

  async function refreshPrices(src, day) {
    try {
      setPrices(await crawlApi.prices(src, day, 100));
    } catch (e) {
      setError(e.message);
    }
  }

  function refreshAll() {
    const { source: s, date: d } = queryRef.current;
    refreshStatus();
    refreshTasks().then(setTasks).catch((e) => setError(e.message));
    refreshLogs();
    refreshPrices(s, d);
  }

  useEffect(() => {
    let alive = true;
    const initialDate = queryRef.current.date;
    refreshStatus();
    refreshTasks().then((t) => { if (alive) setTasks(t); }).catch((e) => { if (alive) setError(e.message); });
    refreshLogs();
    refreshPrices('CJ', initialDate);

    // 采集是异步的：轮询任务，任务全部结束时自动刷新日志与价格数据
    const timer = setInterval(async () => {
      try {
        const t = await crawlApi.tasks();
        if (!alive) return;
        setTasks(t);
        const runningCount = (t.running || []).length;
        if (prevRunning.current > 0 && runningCount === 0) {
          refreshLogs();
          const q = queryRef.current;
          refreshPrices(q.source, q.date);
        }
        prevRunning.current = runningCount;
      } catch {
        // 轮询失败不打断页面
      }
    }, 2500);
    return () => { alive = false; clearInterval(timer); };
  }, []);

  async function handleRun() {
    setBusy(true);
    setNotice(null);
    try {
      const task = await crawlApi.run({ source, mode, date });
      setNotice({ type: 'ok', text: `${task.source} 采集任务已提交（${task.id}），完成后下方数据自动刷新` });
      setTasks(await crawlApi.tasks());
      prevRunning.current = 1;
    } catch (e) {
      setNotice({ type: 'bad', text: e.message });
    } finally {
      setBusy(false);
    }
  }

  function handleQuery(event) {
    event.preventDefault();
    queryRef.current = { source: querySource, date: queryDate };
    refreshPrices(querySource, queryDate);
  }

  const logRows = logs.map((item) => ({
    ...item,
    statusText: item.status === 1 ? '成功' : '失败',
  }));
  const isRunning = (tasks.running || []).includes(source);

  return (
    <div className="crawl-page">
      <header className="crawl-page-head">
        <div>
          <h2>价格爬取</h2>
          <p>四大行情源（SHFE / SGE / CJ / SMM）状态、按需采集与落库结果</p>
        </div>
        <button type="button" className="crawl-btn crawl-btn-ghost" onClick={refreshAll}>刷新</button>
      </header>

      {error && (
        <div className="crawl-alert crawl-alert-bad" onClick={() => setError('')} role="alert">
          后端连接异常：{error}（点击关闭）
        </div>
      )}

      <section className="crawl-section">
        <h3 className="crawl-section-title">源站状态</h3>
        <div className="crawl-status-grid">
          {statusList.length === 0 && <div className="crawl-empty">正在读取后端状态…</div>}
          {statusList.map((item) => (
            <div className="crawl-status-card" key={item.source}>
              <div className="crawl-status-head">
                <strong>{item.source}</strong>
                <span>{SOURCE_LABEL[item.source]}</span>
              </div>
              <div className="crawl-status-meta">
                <span className={`crawl-tag ${item.isTradeDate ? (item.isIntraday ? 'crawl-tag-run' : 'crawl-tag-ok') : 'crawl-tag-off'}`}>
                  {item.isTradeDate ? (item.isIntraday ? '盘中' : '已收盘') : '非交易日'}
                </span>
                <span>收盘 {item.closeTime}</span>
              </div>
              <dl>
                <div><dt>最近已收盘交易日</dt><dd>{item.lastClosedTradeDate || '—'}</dd></div>
                <div><dt>实时口径</dt><dd>{item.supportsRealtime ? '支持' : '不支持'}</dd></div>
              </dl>
              <p className="crawl-status-text">{item.status}</p>
            </div>
          ))}
        </div>
      </section>

      <div className="crawl-two-col">
        <section className="crawl-section">
          <h3 className="crawl-section-title">发起采集</h3>
          <div className="crawl-form">
            <label>
              <span>数据源</span>
              <select value={source} onChange={(e) => setSource(e.target.value)}>
                {SOURCES.map((s) => <option key={s} value={s}>{s} · {SOURCE_LABEL[s]}</option>)}
              </select>
            </label>
            <label>
              <span>采集方式</span>
              <select value={mode} onChange={(e) => setMode(e.target.value)}>
                {Object.entries(MODE_LABEL).map(([key, label]) => (
                  <option key={key} value={key}>{label}</option>
                ))}
              </select>
            </label>
            {mode === 'history' && (
              <label>
                <span>交易日</span>
                <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
              </label>
            )}
            <button
              type="button"
              className="crawl-btn crawl-btn-primary"
              onClick={handleRun}
              disabled={busy || isRunning}
            >
              {isRunning ? `${source} 正在采集…` : '开始采集'}
            </button>
          </div>
          <p className="crawl-hint">
            「按调度决策」由后端守卫判断交易日、盘中实时 / 收盘定稿并做当日幂等；
            「实时行情」仅 SHFE / SGE 支持；采集异步执行，完成后自动刷新价格与日志。
          </p>
          {notice && (
            <div className={`crawl-alert crawl-alert-${notice.type}`}>{notice.text}</div>
          )}
        </section>

        <section className="crawl-section">
          <h3 className="crawl-section-title">采集任务</h3>
          <DataTable
            columns={TASK_COLUMNS}
            rows={tasks.items || []}
            empty="还没有采集任务，点「开始采集」试一次"
            renders={{ status: statusTag, mode: (v) => cell(MODE_LABEL[v] || v) }}
          />
        </section>
      </div>

      <section className="crawl-section">
        <div className="crawl-section-head">
          <h3 className="crawl-section-title">价格数据</h3>
          <form className="crawl-query" onSubmit={handleQuery}>
            <select
              value={querySource}
              onChange={(e) => {
                setQuerySource(e.target.value);
                queryRef.current = { source: e.target.value, date: queryDate };
              }}
            >
              {SOURCES.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
            <input
              type="date"
              value={queryDate}
              onChange={(e) => {
                setQueryDate(e.target.value);
                queryRef.current = { source: querySource, date: e.target.value };
              }}
            />
            <button type="submit" className="crawl-btn crawl-btn-ghost">查询</button>
          </form>
        </div>
        <DataTable
          columns={PRICE_COLUMNS[querySource] || PRICE_COLUMNS.CJ}
          rows={prices}
          empty={`${querySource} 在 ${queryDate} 暂无数据，可切换日期或先发起采集`}
        />
        {prices.length > 0 && <p className="crawl-hint">共 {prices.length} 条（最多展示 100 条）</p>}
      </section>

      <section className="crawl-section">
        <h3 className="crawl-section-title">最近采集日志</h3>
        <DataTable
          columns={LOG_COLUMNS}
          rows={logRows}
          empty="暂无采集日志"
          renders={{ statusText: (v) => (
            <span className={`crawl-tag ${v === '成功' ? 'crawl-tag-ok' : 'crawl-tag-bad'}`}>{v}</span>
          ) }}
        />
      </section>
    </div>
  );
}
