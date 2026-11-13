import React from 'react';
import SalePage from '../pages/Sale';
import PriceCrawlPage from '../pages/PriceCrawl';

/**
 * 菜单的「本地兜底 + 前端组件注册表」。
 *
 * 历史遗留：以前左侧菜单、嵌套路由、页签全部由本文件的 menuConfig 硬编码驱动。
 * 现在菜单数据来自数据库 sys_menu → admin 的 /api/menus（见 MenuContext.jsx），
 * 本文件只保留两样前端必须留在代码里的东西：
 *   1. PANE_COMPONENTS：组件名 → React 组件（数据库里只存组件名字符串）；
 *   2. defaultMenuConfig：接口不可用时的本地兜底菜单，保证页面仍能打开。
 *
 * 新增页面：在这里注册一个组件名，再到 sys_menu 插入一行 component 指向它即可，
 * 不用再改路由表 / 菜单树 / 页签配置。
 */

// 首页：常驻页签，不可关闭；同时是父布局路由的 index 子路由
export const HOME_PANE = {
    path: '/',
    name: '首页',
    closable: false,
    element: <div>欢迎来到首页</div>,
};

/** 数据库 component 字段未登记时的占位页。 */
function PlaceholderPage({ name, path }) {
    return (
        <div className="pane-placeholder">
            <h3>{name || '页面'}</h3>
            <p>菜单路径 <code>{path}</code> 已在数据库中登记，但前端尚未注册对应页面组件。</p>
            <p>在 <code>web/src/config/menuConfig.jsx</code> 的 PANE_COMPONENTS 中补上组件即可。</p>
        </div>
    );
}

/**
 * 组件注册表。值是「接收 pane 描述、返回 React 元素」的函数，
 * 这样占位页能拿到数据库里的名称与路径。
 */
export const PANE_COMPONENTS = {
    SalePage: () => <SalePage />,
    PriceCrawlPage: () => <PriceCrawlPage />,
    Placeholder: (pane) => <PlaceholderPage name={pane.name} path={pane.path} />,
};

// component 为空时，按 path 回退到已有页面（兼容老数据 / 手工改库）
const COMPONENT_BY_PATH = {
    '/sale': 'SalePage',
    '/sale/price-crawl': 'PriceCrawlPage',
};

/** 把一条数据库菜单项解析成可渲染的 element。 */
export function paneElementFor(pane) {
    const name = pane.component || COMPONENT_BY_PATH[pane.path] || 'Placeholder';
    const factory = PANE_COMPONENTS[name] || PANE_COMPONENTS.Placeholder;
    return factory(pane);
}

/** 接口不可用时的兜底菜单，和改造前的静态配置保持一致。 */
export const defaultMenuConfig = [
    {
        key: 'sub1',
        title: '销售管理',
        children: [
            { path: '/sale', name: '销售管理', closable: true, element: <SalePage /> },
            { path: '/sale/price-crawl', name: '价格爬取', closable: true, element: <PriceCrawlPage /> },
            { path: '/sale-config', name: '销售设置', closable: true, element: <div>销售设置内容...</div> },
        ],
    },
];

/**
 * 把 /api/menus 的 groups 转成与 defaultMenuConfig 同形的配置：
 * path 同时是浏览器 URL、Menu.Item key、页签 key；element 由组件注册表解析。
 */
export function hydrateGroups(groups) {
    return (Array.isArray(groups) ? groups : [])
        .map((group, index) => ({
            key: group?.key || `group-${index}`,
            title: group?.title || `分组 ${index + 1}`,
            children: (Array.isArray(group?.children) ? group.children : [])
                .filter((item) => item && typeof item.path === 'string' && item.path.startsWith('/'))
                .map((item) => {
                    const pane = {
                        path: item.path,
                        name: item.name || item.title || item.path,
                        closable: item.closable !== false,
                    };
                    return { ...pane, element: paneElementFor({ ...pane, component: item.component }) };
                }),
        }))
        .filter((group) => group.children.length > 0);
}

/** 菜单配置 → 扁平路由表 + 页签查找表（路由、菜单、页签共用）。 */
export function derivePanes(config) {
    const childPanes = config.flatMap((group) => group.children);
    const allPanes = [HOME_PANE, ...childPanes];
    const byPath = new Map(allPanes.map((pane) => [pane.path, pane]));
    return { childPanes, allPanes, getPaneByPath: (path) => byPath.get(path) };
}

// 静态兜底导出：仅在 MenuProvider 之外（如直接引用）时使用
export const menuConfig = defaultMenuConfig;
export const { childPanes, allPanes, getPaneByPath } = derivePanes(defaultMenuConfig);
