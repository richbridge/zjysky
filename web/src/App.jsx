import './App.css';
// 按需引入：避免加载 kdesign 整包（整包会连带加载未使用的 Tree 等模块并产生警告）
import Layout from '@kdcloudjs/kdesign/es/layout';
import '@kdcloudjs/kdesign/dist/kdesign.css';
import { Navigate, Route, Routes } from 'react-router-dom';

import RichHeader from './pages/RichHeader';
import RichMenu from './pages/RichMenu';
import RichTabs from './pages/RichTabs';
import RichContent from './pages/RichContent';
import { usePaneTabs } from './hooks/usePaneTabs';
import { useMenus } from './config/MenuContext';
import { HOME_PANE } from './config/menuConfig';

/**
 * 父布局路由（嵌套路由的 layout route）。
 * 只负责骨架：Header / 菜单 / 页签栏常驻，内容由 <RichContent /> 的 <Outlet /> 渲染。
 * 页签状态与操作全部来自 usePaneTabs，这里不含业务逻辑。
 */
function App() {
  const { panes, activeKey, openTab, selectTab, closeTab } = usePaneTabs();

  return (
    <Layout className="bodylayout">
      <RichHeader />

      <Layout className="main-layout">
        <RichMenu onMenuClick={openTab} />

        <Layout className="content-layout">
          <RichTabs
            panes={panes}
            activeKey={activeKey}
            onChange={selectTab}
            onClose={closeTab}
          />
          <RichContent />
        </Layout>
      </Layout>
    </Layout>
  );
}

/**
 * 路由表由菜单上下文动态生成：数据库 sys_menu 里加一行，路由 / 菜单 / 页签同时生效。
 * 接口不可用时 MenuProvider 会给出本地兜底菜单，所以这里始终有可用的 childPanes。
 */
export function AppRoutes() {
  const { childPanes, loading } = useMenus();

  return (
    <Routes>
      <Route path="/" element={<App />}>
        <Route index element={HOME_PANE.element} />
        {childPanes.map((pane) => (
          <Route key={pane.path} path={pane.path} element={pane.element} />
        ))}
        {/*
         * 菜单还在加载时不能兜底重定向：否则直接访问「只在数据库里存在」的深层路由，
         * 会在菜单拉回来之前被导航回首页。加载完成后才把未知路径导回首页。
         */}
        <Route
          path="*"
          element={loading
            ? <div className="pane-placeholder">菜单加载中…</div>
            : <Navigate to={HOME_PANE.path} replace />}
        />
      </Route>
    </Routes>
  );
}

export default App;
