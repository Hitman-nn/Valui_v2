import { useState } from 'react';
import type { ReactNode } from 'react';
import {
  Layout,
  Menu,
  Button,
  Space,
  Typography,
  Breadcrumb,
  theme as antTheme,
  Switch,
} from 'antd';
import {
  UserOutlined,
  CreditCardOutlined,
  ApiOutlined,
  DashboardOutlined,
  NotificationOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  BulbOutlined,
  ThunderboltOutlined,
  AuditOutlined,
  BarChartOutlined,
  FieldTimeOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation, Link } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { authApi } from '../api/endpoints';

const { Header, Sider, Content } = Layout;
const { Text } = Typography;

const MENU_ITEMS = [
  { key: '/dashboard', icon: <BarChartOutlined />, label: 'Dashboard' },
  { key: '/users',     icon: <UserOutlined />,      label: 'Users' },
  { key: '/controllers', icon: <ApiOutlined />,     label: 'Controllers' },
  { key: '/events',    icon: <ThunderboltOutlined />, label: 'Events' },
  { key: '/subscriptions', icon: <CreditCardOutlined />, label: 'Tokens' },
  { key: '/parsers',   icon: <DashboardOutlined />,   label: 'Parsers' },
  { key: '/scheduler', icon: <FieldTimeOutlined />,   label: 'Scheduler' },
  { key: '/jobs',      icon: <FieldTimeOutlined />,   label: 'Jobs' },
  { key: '/migration', icon: <ThunderboltOutlined />,  label: 'Migration' },
  { key: '/audit',     icon: <AuditOutlined />,       label: 'Audit Log' },
  { key: '/broadcast', icon: <NotificationOutlined />, label: 'Broadcast' },
];

const BREADCRUMB_MAP: Record<string, string> = {
  dashboard:     'Dashboard',
  users:         'Users',
  controllers:   'Controllers',
  events:        'Events',
  subscriptions: 'Tokens',
  parsers:       'Parsers',
  scheduler:     'Scheduler',
  jobs:          'Jobs',
  migration:     'Migration',
  audit:         'Audit Log',
  broadcast:     'Broadcast',
};

function buildBreadcrumbs(pathname: string): { title: ReactNode }[] {
  const parts = pathname.split('/').filter(Boolean);
  const crumbs: { title: ReactNode }[] = [{ title: <Link to="/">Home</Link> }];
  let path = '';
  parts.forEach((part, i) => {
    path += `/${part}`;
    const label = BREADCRUMB_MAP[part] ?? part;
    if (i === parts.length - 1) {
      crumbs.push({ title: label });
    } else {
      crumbs.push({ title: <Link to={path}>{label}</Link> });
    }
  });
  return crumbs;
}

export default function AdminLayout() {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { telegramId, refreshToken, clearAuth } = useAuthStore();
  const { token } = antTheme.useToken();

  const isDark = localStorage.getItem('valui-theme') !== 'light';

  const handleLogout = async () => {
    if (refreshToken) {
      try {
        await authApi.logout({ refreshToken });
      } catch {
        // ignore errors during logout
      }
    }
    clearAuth();
    navigate('/login');
  };

  const toggleTheme = () => {
    const next = isDark ? 'light' : 'dark';
    localStorage.setItem('valui-theme', next);
    window.location.reload();
  };

  const breadcrumbs = buildBreadcrumbs(location.pathname);
  const selectedKeys = MENU_ITEMS.filter((item) =>
    location.pathname.startsWith(item.key),
  ).map((item) => item.key);

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        collapsible
        collapsed={collapsed}
        trigger={null}
        width={220}
        style={{
          background: token.colorBgContainer,
          borderRight: `1px solid ${token.colorBorderSecondary}`,
          position: 'fixed',
          height: '100vh',
          left: 0,
          top: 0,
          zIndex: 100,
        }}
      >
        <div
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            justifyContent: collapsed ? 'center' : 'flex-start',
            padding: collapsed ? 0 : '0 16px',
            borderBottom: `1px solid ${token.colorBorderSecondary}`,
          }}
        >
          <Typography.Title level={5} style={{ margin: 0, color: token.colorPrimary }}>
            {collapsed ? 'V' : 'Valui Admin'}
          </Typography.Title>
        </div>
        <Menu
          mode="inline"
          selectedKeys={selectedKeys}
          items={MENU_ITEMS}
          style={{ border: 'none', marginTop: 8 }}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>

      <Layout style={{ marginLeft: collapsed ? 80 : 220, transition: 'margin-left 0.2s' }}>
        <Header
          style={{
            position: 'sticky',
            top: 0,
            zIndex: 99,
            padding: '0 16px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            background: token.colorBgContainer,
            borderBottom: `1px solid ${token.colorBorderSecondary}`,
            height: 56,
          }}
        >
          <Button
            type="text"
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed(!collapsed)}
          />
          <Space>
            <Text type="secondary" style={{ fontSize: 13 }}>
              ID: {telegramId}
            </Text>
            <Switch
              checkedChildren={<BulbOutlined />}
              unCheckedChildren={<BulbOutlined />}
              checked={!isDark}
              onChange={toggleTheme}
              size="small"
            />
            <Button
              type="text"
              icon={<LogoutOutlined />}
              onClick={handleLogout}
              danger
            >
              Logout
            </Button>
          </Space>
        </Header>

        <Content style={{ padding: '16px 24px', minHeight: 'calc(100vh - 56px)' }}>
          <Breadcrumb items={breadcrumbs} style={{ marginBottom: 16 }} />
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
