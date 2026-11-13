import Layout from '@kdcloudjs/kdesign/es/layout';
import { Outlet } from 'react-router-dom';

const { Content } = Layout;

/**
 * 内容区：嵌套路由的出口宿主，不接收任何 props。
 */
function RichContent() {
  return (
    <Content className="content-area">
      <div className="content-card">
        <Outlet />
      </div>
    </Content>
  );
}

export default RichContent;
