import Menu from '@kdcloudjs/kdesign/es/menu';
import Sider from '@kdcloudjs/kdesign/es/layout/sider';
import { NavLink, useLocation } from 'react-router-dom';
import { useMenus } from '../config/MenuContext';
import { openTabOnClick, stopBubble } from '../utils/navigation';

/**
 * 左侧菜单：数据来自 MenuContext（数据库 sys_menu，接口不可用时为本地兜底），导航用 <NavLink>。
 *
 * 关键点：
 * - 导航只由 <NavLink> 完成，Menu 的 onClick 仅做幂等的页签登记。
 * - onMouseDown 阻断冒泡：kdesign 的 SubMenu 用 document mousedown 监听
 *   判断"点击外部就关闭弹出层"，但它只认 className 含 menu-item-content-container
 *   的 target；点对 <a> 时会误判为外部点击，在 mousedown→mouseup 之间关掉弹出层，
 *   导致 mouseup/click 脱靶、链接点不动（关闭页签后必现）。在 React 合成事件阶段
 *   阻断 mousedown 冒泡即可规避；弹出层仍由 Menu.Item 自身的 onClick 正常关闭。
 * - 底部状态条显示当前菜单来源，点「刷新」重新读库（改库后无需重新构建前端）。
 */
function RichMenu({ onMenuClick }) {
  const { pathname } = useLocation();
  const { menuConfig, source, updatedAt, loading, error, refresh } = useMenus();
  const fromDatabase = source === 'database' && !error;
  const label = fromDatabase
    ? '菜单来自数据库'
    : source === 'database'
      ? '接口异常·沿用上次菜单'
      : '本地兜底菜单';
  const tip = error
    ? `${error}${updatedAt ? `；上次更新 ${updatedAt}` : ''}`
    : `${source === 'database' ? 'sys_menu' : 'menuConfig.jsx'}；${updatedAt ? `更新于 ${updatedAt}` : '点击刷新重试'}`;

  return (
    <Sider className="menu-sider" width="13em">
      <Menu
        theme="dark"
        className="side-menu"
        inlineIndent={24}
        selectedKey={pathname}
        onClick={({ key }) => onMenuClick(key)}
      >
        {menuConfig.map((group) => (
          <Menu.SubMenu key={group.key} title={group.title}>
            {group.children.map((item) => (
              <Menu.Item key={item.path}>
                <NavLink
                  to={item.path}
                  className={({ isActive }) => 'menu-nav-link' + (isActive ? ' menu-nav-link-active' : '')}
                  onMouseDown={stopBubble}
                  onClick={openTabOnClick(onMenuClick, item.path)}
                >
                  {item.name}
                </NavLink>
              </Menu.Item>
            ))}
          </Menu.SubMenu>
        ))}
      </Menu>

      <div className="menu-source" title={tip}>
        <span className={'menu-source-state' + (fromDatabase ? ' is-db' : ' is-local')}>
          <i className="menu-source-dot" />
          {label}
        </span>
        <button
          type="button"
          className="menu-source-refresh"
          onClick={refresh}
          disabled={loading}
        >
          {loading ? '加载中…' : '刷新'}
        </button>
      </div>
    </Sider>
  );
}

export default RichMenu;
