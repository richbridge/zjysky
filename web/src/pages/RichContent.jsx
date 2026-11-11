import React from 'react';
import Layout from '@kdcloudjs/kdesign/es/layout';
import { useTabs } from '../context/TabsContext';

/**
 * 内容区：只负责渲染当前激活页签对应的内容，不关心页签是怎么来的
 */
function RichContent() {
    const { Content } = Layout;
    const { activePane } = useTabs();

    return (
        <Content style={{ flex: 1, padding: '16px', overflowY: 'auto', background: '#f0f2f5' }}>
            <div style={{ background: '#fff', minHeight: '100%', padding: '20px', borderRadius: '8px' }}>
                {activePane?.component || <div>请选择左侧菜单</div>}
            </div>
        </Content>
    );
}

export default RichContent;
