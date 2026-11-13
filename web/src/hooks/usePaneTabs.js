import { useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useMenus } from '../config/MenuContext';

/**
 * 页签状态与操作（业务方法集中在这里，组件只消费结果）。
 *
 * 设计要点：
 * - openedPaths 记录"用户打开过哪些页签"，只在交互事件里更新
 * - panes 由 openedPaths + 当前 URL + 当前菜单配置派生，不做 effect → state 的同步
 *   这样直接访问深层 URL、浏览器前进后退时，当前页签都会自动出现
 * - 菜单配置来自数据库（MenuContext），刷新菜单后页签名称会同步更新
 * - 当前激活项始终以 URL 为准，panes 只负责页签栏显示哪些项
 */
export function usePaneTabs() {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const { HOME_PANE, getPaneByPath } = useMenus();

  // 首页不进 openedPaths：页签栏只用 RichTabs 的左侧图标作为首页入口
  // 初始直接进入深层 URL 时，把该业务页签记入打开记录
  const [openedPaths, setOpenedPaths] = useState(() =>
    pathname === HOME_PANE.path ? [] : [pathname]
  );

  const panes = useMemo(() => {
    const paths = [...new Set([...openedPaths, pathname])];
    return paths
      .map(getPaneByPath)
      .filter((pane) => pane && pane.path !== HOME_PANE.path);
  }, [openedPaths, pathname, getPaneByPath, HOME_PANE.path]);

  // 菜单点击：<NavLink> 完成跳转，这里只登记页签（幂等）
  const openTab = (path) => {
    if (path === HOME_PANE.path || !getPaneByPath(path)) return;
    setOpenedPaths((prev) => (prev.includes(path) ? prev : [...prev, path]));
  };

  // 关闭页签：若关的是当前页，回退到左边相邻页签，其次右边，最后首页
  const closeTab = (targetPath, event) => {
    event?.stopPropagation();
    if (targetPath === HOME_PANE.path) return;

    const index = panes.findIndex((pane) => pane.path === targetPath);
    if (index === -1) return;

    setOpenedPaths((prev) => prev.filter((path) => path !== targetPath));

    if (pathname === targetPath) {
      const fallback = panes[index - 1] || panes[index + 1] || HOME_PANE;
      navigate(fallback.path, { replace: true });
    }
  };

  // 兜底切换：点到链接之外的页签 / 菜单区域时仍可导航
  const selectTab = (path) => {
    openTab(path);
    if (pathname !== path) navigate(path);
  };

  return { panes, activeKey: pathname, openTab, selectTab, closeTab };
}

export default usePaneTabs;
