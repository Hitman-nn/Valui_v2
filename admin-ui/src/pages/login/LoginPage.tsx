import { Card, Form, Input, Button, Typography, App } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useState } from 'react';
import { authApi } from '../../api/endpoints';
import { useAuthStore } from '../../store/authStore';

interface LoginFormValues {
  telegramId: string;
  adminPassword: string;
}

function decodeJwt(token: string): Record<string, unknown> {
  try {
    const payload = token.split('.')[1];
    return JSON.parse(atob(payload)) as Record<string, unknown>;
  } catch {
    return {};
  }
}

export default function LoginPage() {
  const [loading, setLoading] = useState(false);
  const { setAuth } = useAuthStore();
  const navigate = useNavigate();
  const { notification } = App.useApp();

  const onFinish = async (values: LoginFormValues) => {
    setLoading(true);
    try {
      const data = await authApi.login({
        telegramId: Number(values.telegramId),
        adminPassword: values.adminPassword,
      });

      const payload = decodeJwt(data.accessToken);
      const telegramId = typeof payload.telegramId === 'number' ? payload.telegramId : 0;
      const role = typeof payload.role === 'string' ? payload.role : 'ADMIN';

      setAuth(data.accessToken, data.refreshToken, telegramId, role);
      navigate('/');
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : 'Login failed. Check your credentials.';
      notification.error({ message: 'Authentication failed', description: message });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'var(--ant-color-bg-layout)',
      }}
    >
      <Card
        style={{ width: 380, boxShadow: '0 4px 24px rgba(0,0,0,0.18)' }}
        styles={{ body: { padding: 32 } }}
      >
        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <Typography.Title level={3} style={{ marginBottom: 4 }}>
            Valui Admin
          </Typography.Title>
          <Typography.Text type="secondary">Sign in to admin panel</Typography.Text>
        </div>

        <Form<LoginFormValues> layout="vertical" onFinish={onFinish} requiredMark={false}>
          <Form.Item
            label="Telegram ID"
            name="telegramId"
            rules={[
              { required: true, message: 'Enter your Telegram ID' },
              {
                pattern: /^\d+$/,
                message: 'Telegram ID must be a number',
              },
            ]}
          >
            <Input
              prefix={<UserOutlined />}
              placeholder="123456789"
              size="large"
              autoComplete="username"
            />
          </Form.Item>

          <Form.Item
            label="Admin Password"
            name="adminPassword"
            rules={[{ required: true, message: 'Enter your admin password' }]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="Password"
              size="large"
              autoComplete="current-password"
            />
          </Form.Item>

          <Form.Item style={{ marginBottom: 0, marginTop: 8 }}>
            <Button
              type="primary"
              htmlType="submit"
              block
              size="large"
              loading={loading}
            >
              Sign In
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}
