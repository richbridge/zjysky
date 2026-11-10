import './App.css';
import { Layout, Menu, Tabs, Button, Icon } from '@kdcloudjs/kdesign';
import '@kdcloudjs/kdesign/dist/kdesign.css';
import { Settings } from '@kdcloudjs/kdesign-icons';
import logo from './assets/logo.png';
import React, { useState } from 'react';

// 引入你的业务组件
import SalePage from './pages/sale';

function App() {
    const { Header, Content, Sider } = Layout;

    // 1. 菜单与页签及组件的映射配置表
    const menuConfig = {
        '1-1': { name: '销售管理', value: 'sale', closable: true, component: <SalePage /> },
        '1-2': { name: '销售设置', value: 'sale-config', closable: true, component: <div>销售设置内容...</div> },
        '2-1': { name: '培训管理', value: 'train', closable: true, component: <div>培训管理内容...</div> },
        '2-2': { name: '培训设置', value: 'train-config', closable: true, component: <div>培训设置内容...</div> },
        '3-1': { name: '制度管理', value: 'system', closable: true, component: <div>制度管理内容...</div> },
        '3-2': { name: '制度设置', value: 'system-config', closable: true, component: <div>制度设置内容...</div> },
    };

    // 2. 初始页签状态
    const [panes, setPanes] = useState([
        { name: '首页', value: 'home', closable: false, component: <div>欢迎来到首页</div> },
    ]);

    const [activeKey, setActiveKey] = useState('home');

    // 3. 切换页签
    const onChange = (key) => {
        setActiveKey(key);
    };

    // 4. 移除页签（适配官方 API）
    const remove = (targetKey, e) => {
        if(e) e.stopPropagation(); // 防止点击关闭时触发页签切换

        let lastIndex = 0;
        panes.forEach((pane, i) => {
            if (pane.value === targetKey) {
                lastIndex = i - 1;
            }
        });

        const newPanes = panes.filter((pane) => pane.value !== targetKey);

        if (newPanes.length && activeKey === targetKey) {
            if (lastIndex >= 0) {
                setActiveKey(newPanes[lastIndex].value);
            } else {
                setActiveKey(newPanes[0].value);
            }
        }
        setPanes(newPanes);
    };

    // 5. 处理左侧菜单点击
    const handleMenuClick = ({ key }) => {
        const targetPane = menuConfig[key];
        if (!targetPane) return;

        const paneExists = panes.find((pane) => pane.value === targetPane.value);

        if (!paneExists) {
            setPanes([...panes, targetPane]);
        }
        setActiveKey(targetPane.value);
    };

    // 顶部 Header 用的假数据
    const data = Array(6).fill(0).map((v, i) => ({ name: `TabPane${i}`, value: i }));

    return (
        <Layout className={'bodylayout'}>

            {/* 顶部 Header */}
            <Header
                className={'header'}
                style={{ height: '4em', '--kd-c-tabs-card-color-background': 'transparent', display: 'flex', alignItems: 'center', padding: 0, background: '#fff', borderBottom: '1px solid #e5e4e7' }}
            >
                <div className={'header-div'} style={{ width: '13em', display: 'flex', justifyContent: 'center', alignItems: 'center', flexShrink: 0 }}>
                    <img src={logo} alt={'logo'} style={{ height: '2.5em', width: 'auto' }} />
                </div>
                <div style={{ flex: 1, height: '100%', display: 'flex', alignItems: 'center', overflow: 'hidden' }}>
                    <Tabs type="card" style={{ width: '100%' }}>
                        {data.map((v) => (
                            <Tabs.TabPane key={v.value} tab={v.name} />
                        ))}
                    </Tabs>
                </div>
            </Header>

            {/* 下方主体区域 */}
            <Layout style={{ flex: 1, minHeight: 0 }}>

                {/* 左侧菜单栏 */}
                <Sider width={'13em'} style={{ height: '100%' }}>
                    <Menu
                        theme="dark"
                        style={{ width: '100%', border: 'none' }}
                        inlineIndent={24}
                        onClick={handleMenuClick}
                    >
                        <Menu.SubMenu key="sub1" title={"销售管理"} icon={<Settings />}>
                            <Menu.Item key="1-1">销售管理</Menu.Item>
                            <Menu.Item key="1-2">销售设置</Menu.Item>
                        </Menu.SubMenu>
                        <Menu.SubMenu key="sub2" title="培训管理">
                            <Menu.Item key="2-1">培训管理</Menu.Item>
                            <Menu.Item key="2-2">培训设置</Menu.Item>
                        </Menu.SubMenu>
                        <Menu.SubMenu key="sub3" title="制度管理">
                            <Menu.Item key="3-1">制度管理</Menu.Item>
                            <Menu.Item key="3-2">制度设置</Menu.Item>
                        </Menu.SubMenu>
                    </Menu>
                </Sider>

                {/* 右侧区域：页签 + 内容 */}
                <Layout style={{ height: '100%', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>

                    {/* 页签栏 */}
                    <div style={{ background: '#fff' }}>
                        <Tabs
                            type="dynamic"
                            showScrollArrow
                            activeKey={activeKey}
                            onChange={onChange}
                        >
                            <Tabs.TabPane specialPane="left">
                                <Icon
                                    type="workbench"
                                    style={{
                                        color: '#999999',
                                        fontSize: '20px',
                                    }}
                                />
                            </Tabs.TabPane>

                            {panes.map((pane) => (
                                <Tabs.TabPane
                                    key={pane.value}
                                    tab={pane.name}
                                    // 核心修改：使用官方 Demo 的 operations API 注入关闭按钮
                                    operations={
                                        pane.closable ? [
                                            <Button
                                                key="close"
                                                type="text"
                                                style={{ padding: 0, height: 'auto', minWidth: 'auto' }}
                                                onClick={(e) => remove(pane.value, e)}
                                            >
                                                <Icon type="close" style={{ fontSize: '12px', color: '#999' }} />
                                            </Button>
                                        ] : []
                                    }
                                />
                            ))}
                        </Tabs>
                    </div>

                    {/* 内容区：动态渲染组件 */}
                    <Content style={{ flex: 1, padding: '1em', overflowY: 'auto', background: '#f0f2f5' }}>
                        <div style={{ background: '#fff', minHeight: '100%', padding: '20px' }}>
                            {panes.find((pane) => pane.value === activeKey)?.component || <div>请选择左侧菜单</div>}
                        </div>
                    </Content>

                </Layout>
            </Layout>
        </Layout>
    );
}

export default App;