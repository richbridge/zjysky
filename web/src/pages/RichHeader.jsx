import Tabs from '@kdcloudjs/kdesign/es/tabs';
import Layout from '@kdcloudjs/kdesign/es/layout';
import logo from '../assets/logo.png';
import { headerMenu } from '../config/headerConfig';

const { Header } = Layout;

/**
 * 顶部 Header：菜单数据来自 config/headerConfig，样式集中在 App.css。
 */
function RichHeader() {
  return (
    <Header className="header">
      <div className="header-logo">
        <img src={logo} alt="logo" />
      </div>
      <div className="header-tabs">
        <Tabs type="card" effect="scrollx">
          {headerMenu.map((item) => (
            <Tabs.TabPane key={item.value} tab={item.name} />
          ))}
        </Tabs>
      </div>
    </Header>
  );
}

export default RichHeader;
