import React from 'react';
import Menu from '@kdcloudjs/kdesign/es/menu';
import Sider from '@kdcloudjs/kdesign/es/layout/sider';
import { useTabs } from '../context/TabsContext';

// 菜单只负责“点了哪一项”，页签由 TabsContext 统一管理
function RichMenu() {
    const { openTab } = useTabs();

    const handleMenuClick = ({ key }) => openTab(key);

    return (
        <Sider width={'13em'} style={{ height: '100%' }}>
            <Menu
                theme="dark"
                style={{ width: '100%', border: 'none' }}
                inlineIndent={24}
                onClick={handleMenuClick}
            >
                <Menu.SubMenu key="sub1" title={"销售管理"}>
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
    );
}

export default RichMenu;
