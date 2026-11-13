/**
 * 菜单接口封装：/api/menus → 数据库 sys_menu。
 *
 * 开发环境走 Vite 的 /api 代理；生产环境把 VITE_MENU_API 指到 admin 的实际地址
 * （例如 http://host:8080/api/menus），页面代码不用改。
 *
 * 所有请求统一使用 GET：读操作无参数或少量 query，写操作把字段放在 query 上，
 * 并禁用浏览器缓存，避免 GET 写请求被缓存后不生效。
 */
const BASE = import.meta.env.VITE_MENU_API || '/api/menus';

function toQuery(params) {
    if (!params) return '';
    const search = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
        if (value !== undefined && value !== null) {
            search.set(key, String(value));
        }
    });
    const qs = search.toString();
    return qs ? `?${qs}` : '';
}

async function request(path = '', params = {}) {
    let response;
    try {
        response = await fetch(`${BASE}${path}${toQuery(params)}`, {
            headers: { Accept: 'application/json' },
            cache: 'no-store',
        });
    } catch (error) {
        throw new Error(`无法连接菜单接口（${error.message}）。请确认 admin 已启动，且 Vite 代理目标正确。`);
    }

    if (!response.ok) {
        const detail = await response.text().catch(() => '');
        let message = detail;
        try {
            const parsed = JSON.parse(detail);
            message = parsed.message || parsed.detail || detail;
        } catch {
            // 纯文本错误，保持原样
        }
        throw new Error(`${response.status} ${message || '请求失败'}`.slice(0, 300));
    }
    return response.json();
}

export const menuApi = {
    /** 分组树：{source, table, count, updatedAt, groups:[...]} */
    tree: () => request(''),
    /** 扁平原始行（含隐藏项），排查数据用 */
    rows: () => request('/rows'),
    /** 新增 / 修改一行：fields 带 id 为修改，不带为新增；未传字段在修改时继承库中现值 */
    save: (fields) => request('/save', fields),
    /** 删除一行：删分组时连带删除其子项 */
    remove: (id) => request('/remove', { id }),
};
