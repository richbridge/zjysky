import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { AppRoutes } from './App.jsx'
import { MenuProvider } from './config/MenuContext'
import './index.css'

/*
 * 嵌套路由：
 * - 父路由 App 是整页布局（Header + Menu + Tabs + Content），本身不含业务内容
 * - 子路由由数据库菜单动态生成，渲染进 App 里的 <Outlet />
 * - index 命中首页；* 兜底重定向回首页
 */
createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <MenuProvider>
        <AppRoutes />
      </MenuProvider>
    </BrowserRouter>
  </StrictMode>,
)
