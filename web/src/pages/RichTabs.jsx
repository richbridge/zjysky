import React from 'react';
import Tabs from '@kdcloudjs/kdesign/es/tabs';
import Icon from '@kdcloudjs/kdesign/es/icon';
import { useTabs } from '../context/TabsContext';

/**
 * 页签栏：只负责渲染页签导航（切换 / 关闭 / 回首页），不渲染任何业务内容
 */
function RichTabs() {
    const { panes, activeKey, selectTab, closeTab, goHome } = useTabs();

    return (
        <div className="custom-tabs-container">
            <Tabs type="dynamic" effect="scrollx" showScrollArrow activeKey={activeKey} onChange={selectTab}>
                <Tabs.TabPane specialPane="left">
                    <Icon
                        type="workbench"
                        onClick={goHome}
                        style={{
                            color: '#999',
                            fontSize: '20px',
                            cursor: 'pointer',
                            padding: '0 12px',
                            display: 'flex',
                            alignItems: 'center',
                        }}
                    />
                </Tabs.TabPane>

                {panes.map((pane) => (
                    <Tabs.TabPane
                        key={pane.value}
                        tab={
                            <div className="custom-tab-label">
                                <span>{pane.name}</span>
                                {pane.closable && (
                                    <span className="custom-tab-close" onClick={(e) => closeTab(pane.value, e)}>
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
