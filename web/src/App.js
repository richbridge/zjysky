import './App.css';
import { Layout, Menu, Tabs, Button, Icon } from '@kdcloudjs/kdesign';
import '@kdcloudjs/kdesign/dist/kdesign.css';
import { Settings } from '@kdcloudjs/kdesign-icons';
import logo from './logo.png';
import React, { useState } from 'react';

function App() {
    const { Header, Content, Sider } = Layout;

    // 1. 页签数据源
    const [panes, setPanes] = useState([
        { name: '首页', value: 'home', closable: false },
        { name: '应用', value: 'app', closable: false },
        { name: '总账', value: 'gl', closable: true },
        { name: '出纳', value: 'cash', closable: true },
    ]);

    const [activeKey, setActiveKey] = useState('home');


    // 2. 切换页签
    const onChange = (key) => {
        setActiveKey(key);
    };

    // 3. 移除页签
    const remove = (targetKey) => {
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

    // 5. 自定义删除按钮
    const removeBtn = (key) => (
        <Button
            type="text"
            style={{ padding: 0, height: 'auto', lineHeight: '1', marginLeft: '4px' }}
            onClick={(e) => {
                e.stopPropagation();
                remove(key);
            }}
        >
            <Icon type="close" style={{ fontSize: '12px' }} />
        </Button>
    );

    const data = Array(5)
        .fill(0)
        .map((v, i) => {
            return {
                name: `TabPane${i}`,
                value: i
            }
        })

    return (
        <Layout style={{ height: '100vh', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>

            {/* 顶部 Header */}
            <Header
                style={{
                    width: '100%',
                    height: '4em',
                    lineHeight: '4em',
                    display: 'flex',
                    borderBottom: '1px solid #f0f0f0',
                    padding: 0,
                    '--kd-c-tabs-card-color-background': 'transparent',
                }}
            >
                <div style={{ width: "14em",display: 'flex',justifyContent:'center'}}>
                    <img src={logo} alt="Logo" />
                </div>

                <div
                    style={{
                        width: '100%',
                    }}>

                    <Tabs
                        type="card"
                        effect="fade"
                        defaultActiveKey={'home'}
                    >
                        <Tabs.TabPane key={'home'} tab={'首页'} />
                        {data.map((v) => (
                            <Tabs.TabPane key={v.value} tab={v.name} />
                        ))}
                    </Tabs>
                </div>
            </Header>

            {/* 下方主体区域 */}
            <Layout style={{ flex: 1, minHeight: 0 }}>

                {/* 左侧菜单栏 */}
                <Sider
                    width={'13em'}
                    style={{
                        height: '100%',
                        backgroundColor: '#343848',
                        overflow: 'hidden',
                        flexShrink: 0,
                        position: 'relative'
                    }}
                >
                    <div style={{
                        position: 'absolute',
                        top: 0,
                        left: 0,
                        right: 0,
                        bottom: 0,
                        overflowY: 'auto',
                        overflowX: 'hidden'
                    }}>
                        <Menu
                            theme="dark"
                            mode="vertical"
                            style={{ width: '100%', border: 'none' }}
                            inlineIndent={24}
                        >
                            <Menu.SubMenu key="sub1" title={"销售管理"} icon={< Settings />}>
                                <Menu.Item key="1-1">销售看板</Menu.Item>
                                <Menu.Item key="1-2">销售数据</Menu.Item>
                                <Menu.Item key="1-3">价格数据</Menu.Item>
                                <Menu.Item key="1-4">价格采集</Menu.Item>
                            </Menu.SubMenu>
                            <Menu.SubMenu key="sub2" title="培训管理">
                                <Menu.Item key="2-1">标签二-1</Menu.Item>
                                <Menu.Item key="2-2">标签二-2</Menu.Item>
                            </Menu.SubMenu>
                            <Menu.SubMenu key="sub3" title="制度管理">
                                <Menu.Item key="3-1">标签二-1</Menu.Item>
                                <Menu.Item key="3-2">标签二-2</Menu.Item>
                            </Menu.SubMenu>
                        </Menu>
                    </div>
                </Sider>

                {/* 右侧区域：页签 + 内容 */}
                <Layout style={{ height: '100%', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>

                    {/* 页签栏：移除了 Dropdown 包裹 */}
                    <div style={{ background: '#fff'}}>
                        <Tabs
                            type="line"
                            effect="fade"
                            showScrollArrow
                            activeKey={activeKey}
                            onChange={onChange}
                        >
                            {panes.map((pane) => (
                                <Tabs.TabPane
                                    key={pane.value}
                                    tab={pane.name}
                                    closable={pane.closable}
                                    closeIcon={removeBtn(pane.value)}
                                >
                                    <div style={{ padding: '0 2em', minHeight: '1em' }}>
                                        这里是 {pane.name} 的内容区域
                                    </div>
                                </Tabs.TabPane>
                            ))}
                        </Tabs>
                    </div>

                    {/* 内容区 */}
                    <Content style={{ flex: 1, padding: '1em', overflowY: 'auto', background: '#f0f2f5' }}>
                        <div style={{ background: '#fff', minHeight: '100%', padding: '20px' }}>
                            内容区
                        </div>
                    </Content>
                </Layout>

            </Layout>
        </Layout>
    );
}

export default App;