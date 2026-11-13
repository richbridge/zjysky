import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { menuApi } from '../api/menu';
import { HOME_PANE, defaultMenuConfig, derivePanes, hydrateGroups } from './menuConfig';

/**
 * 菜单数据上下文：应用启动时从数据库拉取一次，之后由「刷新」按钮按需重拉。
 *
 * - 成功：用数据库分组树生成左侧菜单、嵌套路由、页签三处共用的配置；
 * - 失败：保留本地兜底菜单（或上一次成功加载的菜单），页面仍然可用；
 * - 数据库改名 / 排序 / 隐藏后，点菜单底部「刷新」即可生效，不用重新构建前端。
 */
const initialState = {
    HOME_PANE,
    menuConfig: defaultMenuConfig,
    ...derivePanes(defaultMenuConfig),
    source: 'local',
    updatedAt: null,
    // 首次渲染时菜单还没拉回来：为 true 才能避免数据库里的新路由被兜底路由提前重定向
    loading: true,
    error: null,
    refresh: () => {},
};

const MenuContext = createContext(initialState);

export function MenuProvider({ children }) {
    const [state, setState] = useState(initialState);

    const refresh = useCallback(async () => {
        setState((prev) => ({ ...prev, loading: true }));
        try {
            const data = await menuApi.tree();
            const groups = hydrateGroups(data?.groups);
            if (!groups.length) {
                throw new Error('数据库里没有可用菜单（sys_menu 为空或全部隐藏）');
            }
            setState({
                ...initialState,
                menuConfig: groups,
                ...derivePanes(groups),
                source: 'database',
                updatedAt: data?.updatedAt || null,
                loading: false,
                error: null,
            });
        } catch (error) {
            // 接口挂了不影响页面：保留兜底菜单或上一次成功加载的菜单
            setState((prev) => ({
                ...prev,
                loading: false,
                error: error?.message || '菜单接口不可用',
            }));
        }
    }, []);

    useEffect(() => {
        refresh();
    }, [refresh]);

    const value = useMemo(() => ({ ...state, refresh }), [state, refresh]);
    return <MenuContext.Provider value={value}>{children}</MenuContext.Provider>;
}

export const useMenus = () => useContext(MenuContext);

export default MenuContext;
