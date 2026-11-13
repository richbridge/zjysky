/**
 * 「价格爬取」页的后端接口封装。
 *
 * 开发环境走 Vite 的 /api 代理（见 vite.config.js），生产环境把
 * VITE_API_BASE 指到 admin 的实际地址即可，页面代码不用改。
 *
 * 所有接口统一使用 GET，包括触发采集；参数一律走 query，并禁用浏览器缓存。
 */
const BASE = import.meta.env.VITE_API_BASE || '/api/crawl';

function toQuery(params) {
  if (!params) return '';
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value));
    }
  });
  const qs = search.toString();
  return qs ? `?${qs}` : '';
}

async function request(path, { params } = {}) {
  let response;
  try {
    response = await fetch(`${BASE}${path}${toQuery(params)}`, {
      headers: { Accept: 'application/json' },
      cache: 'no-store',
    });
  } catch (error) {
    throw new Error(`无法连接后端（${error.message}）。请确认 admin 已启动，且 Vite 代理目标正确。`);
  }

  if (!response.ok) {
    const detail = await response.text().catch(() => '');
    let message = detail;
    // Spring 的 ResponseStatusException 会返回 ProblemDetail JSON，只取其中的 detail
    try {
      const parsed = JSON.parse(detail);
      message = parsed.detail || parsed.message || detail;
    } catch {
      // 纯文本错误，保持原样
    }
    throw new Error(`${response.status} ${message || '请求失败'}`.slice(0, 300));
  }
  return response.json();
}

export const crawlApi = {
  /** 四源交易日 / 盘中 / 收盘状态 */
  status: () => request('/status'),

  /** 触发采集：{ source, mode: scheduled|realtime|history, date } */
  run: ({ source, mode, date }) => request('/run', {
    params: { source, mode, date: mode === 'history' ? date : undefined },
  }),

  /** 最近任务 + 正在运行的源 */
  tasks: (limit = 10) => request('/tasks', { params: { limit } }),

  /** 采集日志（md_crawl_log） */
  logs: (source, limit = 20) => request('/logs', { params: { source, limit } }),

  /** 指定源、指定日期的价格数据 */
  prices: (source, date, limit = 100) => request('/prices', { params: { source, date, limit } }),
};
