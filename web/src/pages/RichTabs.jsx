import Tabs from '@kdcloudjs/kdesign/es/tabs';
import Icon from '@kdcloudjs/kdesign/es/icon';
import { Link, useLocation } from 'react-router-dom';
import { HOME_PANE } from '../config/menuConfig';
import { stopBubble } from '../utils/navigation';

/**
 * 页签栏：只负责切换 / 关闭 / 回首页的导航，不渲染业务内容。
 * - 首页不进 panes，只用 specialPane="left" 的图标作为唯一入口
 * - 当前处于首页时，图标加 home-nav-link-active 显示激活色
 * - 业务页签标题用 <Link>，激活态由 URL（activeKey）驱动
 * - onClick 用 stopBubble 阻断冒泡到 kdesign 的 onChange，Router 独占跳转
 * - 关闭按钮放在 Link 之外，避免误触跳转
 */
function RichTabs({ panes, activeKey, onChange, onClose }) {
  const { pathname } = useLocation();
  const isHome = pathname === HOME_PANE.path;

  return (
    <div className="custom-tabs-container">
      <Tabs type="dynamic" effect="scrollx" showScrollArrow activeKey={activeKey} onChange={onChange}>
        <Tabs.TabPane specialPane="left">
          <Link
            to={HOME_PANE.path}
            className={'home-nav-link' + (isHome ? ' home-nav-link-active' : '')}
            title="返回首页"
            onClick={stopBubble}
          >
            <Icon type="workbench" className="home-nav-icon" />
          </Link>
        </Tabs.TabPane>

        {panes.map((pane) => (
          <Tabs.TabPane
            key={pane.path}
            tab={
              <div className="custom-tab-label">
                <Link to={pane.path} className="tab-nav-link" onClick={stopBubble}>
                  {pane.name}
                </Link>
                {pane.closable && (
                  <span className="custom-tab-close" onClick={(event) => onClose(pane.path, event)}>
                    ✕
                  </span>
                )}
              </div>
            }
          />
        ))}
      </Tabs>
    </div>
  );
}

export default RichTabs;
