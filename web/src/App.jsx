import './App.css';
// 按需引入：避免加载 kdesign 整包（会输出整包引入提示，并连带加载未使用的 Tree 等模块）
import Layout from '@kdcloudjs/kdesign/es/layout';
import '@kdcloudjs/kdesign/dist/kdesign.css';
import React from 'react';

import RichHeader from './pages/RichHeader';
import RichMenu from './pages/RichMenu';
import RichTabs from './pages/RichTabs';
import RichContent from './pages/RichContent';
import { TabsProvider } from './context/TabsContext';

function App() {
    return (
        <TabsProvider>
            <Layout className={'bodylayout'}>

                <RichHeader />

                <Layout style={{ flex: 1, minHeight: 0 }}>
                    {/* 左侧菜单 */}
                    <RichMenu />

                    {/* 右侧区域：页签栏与内容区已拆成两个独立组件，共享 TabsContext */}
                    <Layout style={{ height: '100%', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
                        <RichTabs />
                        <RichContent />
                    </Layout>
                </Layout>
            </Layout>
        </TabsProvider>
    );
}

export default App;
