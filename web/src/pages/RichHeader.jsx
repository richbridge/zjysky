    import Tabs from '@kdcloudjs/kdesign/es/tabs';
    import Layout from '@kdcloudjs/kdesign/es/layout';
    import logo from '../assets/logo.png';
    import '../App.css';

    function RichHeader() {

        const { Header } = Layout;

        // 顶部 Header 用的假数据
        const data = Array(6).fill(0).map((v, i) => ({ name: `TabPane${i}`, value: i }));

        return (
            /* 顶部 Header */
            <Header
                className={'header'}
                style={{ height: '4em', '--kd-c-tabs-card-color-background': 'transparent', display: 'flex', alignItems: 'center', padding: 0, background: '#fff', borderBottom: '1px solid #e5e4e7' }}
            >

                <div className={'header-div'} style={{ width: '13em', display: 'flex', justifyContent: 'center', alignItems: 'center', flexShrink: 0 }}>
                    <img src={logo} alt={'logo'} style={{ height: '2.5em', width: 'auto' }} />
                </div>

                <div style={{ flex: 1, height: '100%', display: 'flex', alignItems: 'center', overflow: 'hidden' }}>
                    <Tabs type="card" effect="scrollx" style={{ width: '100%' }}>
                        {data.map((v) => (
                            <Tabs.TabPane key={v.value} tab={v.name} />
                        ))}
                    </Tabs>
                </div>
            </Header>
        )
    }

    export default RichHeader;
