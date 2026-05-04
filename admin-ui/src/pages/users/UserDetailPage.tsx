import { useState } from 'react';
import {
  Card,
  Descriptions,
  Tabs,
  Table,
  Button,
  Modal,
  Form,
  Select,
  Space,
  Typography,
  Tag,
  Spin,
  App,
} from 'antd';
import { ArrowLeftOutlined, StopOutlined, CheckCircleOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useParams, useNavigate } from 'react-router-dom';
import dayjs from 'dayjs';
import { usersApi, controllersApi, subscriptionsApi } from '../../api/endpoints';
import { StatusBadge, RoleBadge } from '../../components/StatusBadge';
import type { AuditLog, NotificationLog, Controller } from '../../api/types';

const PLAN_OPTIONS = [
  { label: 'FREE', value: 'FREE' },
  { label: 'PRO', value: 'PRO' },
  { label: 'PREMIUM', value: 'PREMIUM' },
];

export default function UserDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { notification } = App.useApp();
  const qc = useQueryClient();
  const [auditPage, setAuditPage] = useState(0);
  const [notifPage, setNotifPage] = useState(0);
  const [ctrlPage, setCtrlPage] = useState(0);
  const [grantOpen, setGrantOpen] = useState(false);
  const [grantForm] = Form.useForm<{ planCode: string }>();

  const { data: user, isLoading: userLoading } = useQuery({
    queryKey: ['user', id],
    queryFn: () => usersApi.get(id!),
    enabled: !!id,
  });

  const { data: auditData, isLoading: auditLoading } = useQuery({
    queryKey: ['user-audit', id, auditPage],
    queryFn: () => usersApi.auditLog(id!, { page: auditPage, size: 10 }),
    enabled: !!id,
  });

  const { data: notifData, isLoading: notifLoading } = useQuery({
    queryKey: ['user-notif', id, notifPage],
    queryFn: () => usersApi.notifications(id!, { page: notifPage, size: 10 }),
    enabled: !!id,
  });

  const { data: ctrlData, isLoading: ctrlLoading } = useQuery({
    queryKey: ['controllers', ctrlPage],
    queryFn: () => controllersApi.list({ page: ctrlPage, size: 10 }),
  });

  const banMutation = useMutation({
    mutationFn: () =>
      user?.status === 'BANNED' ? usersApi.unban(id!) : usersApi.ban(id!),
    onSuccess: () => {
      notification.success({ message: 'User status updated' });
      qc.invalidateQueries({ queryKey: ['user', id] });
      qc.invalidateQueries({ queryKey: ['users'] });
    },
    onError: (err: Error) => notification.error({ message: err.message }),
  });

  const grantMutation = useMutation({
    mutationFn: (planCode: string) => subscriptionsApi.grant(id!, { planCode }),
    onSuccess: () => {
      notification.success({ message: 'Plan granted' });
      setGrantOpen(false);
      grantForm.resetFields();
      qc.invalidateQueries({ queryKey: ['user', id] });
    },
    onError: (err: Error) => notification.error({ message: err.message }),
  });

  if (userLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 64 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!user) {
    return (
      <Typography.Text type="danger">User not found</Typography.Text>
    );
  }

  const userControllers = (ctrlData?._embedded?.controllers ?? []).filter(
    (c: Controller) => c.ownerTelegramId === user.telegramId,
  );

  const auditColumns = [
    { title: 'Action', dataIndex: 'action', key: 'action' },
    { title: 'Entity', dataIndex: 'entityType', key: 'entityType', render: (v: string | null) => v ?? '—' },
    { title: 'Entity ID', dataIndex: 'entityId', key: 'entityId', render: (v: string | null) => v ?? '—' },
    { title: 'IP', dataIndex: 'ipAddress', key: 'ip', render: (v: string | null) => v ?? '—' },
    {
      title: 'Date',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (v: string) => dayjs(v).format('DD.MM.YYYY HH:mm'),
    },
  ];

  const notifColumns = [
    { title: 'Channel', dataIndex: 'channel', key: 'channel' },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      render: (v: string) => (
        <Tag color={v === 'SENT' ? 'success' : v === 'FAILED' ? 'error' : 'default'}>{v}</Tag>
      ),
    },
    { title: 'Attempts', dataIndex: 'attempts', key: 'attempts', width: 90 },
    {
      title: 'Sent At',
      dataIndex: 'sentAt',
      key: 'sentAt',
      render: (v: string | null) => (v ? dayjs(v).format('DD.MM.YYYY HH:mm') : '—'),
    },
    {
      title: 'Created',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (v: string) => dayjs(v).format('DD.MM.YYYY HH:mm'),
    },
  ];

  const ctrlColumns = [
    { title: 'Bookmaker', dataIndex: 'bookmaker', key: 'bookmaker' },
    { title: 'Title', dataIndex: 'title', key: 'title', render: (v: string | null) => v ?? '—' },
    {
      title: 'Active',
      dataIndex: 'isActive',
      key: 'isActive',
      render: (v: boolean) => <Tag color={v ? 'success' : 'default'}>{v ? 'Yes' : 'No'}</Tag>,
    },
    { title: 'Events', dataIndex: 'detectedEventsCount', key: 'events' },
    {
      title: 'Last Check',
      dataIndex: 'lastCheckedAt',
      key: 'lastCheckedAt',
      render: (v: string | null) => (v ? dayjs(v).format('DD.MM.YYYY HH:mm') : '—'),
    },
  ];

  return (
    <>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/users')}>
          Back to Users
        </Button>
        <Button
          danger={user.status !== 'BANNED'}
          icon={user.status === 'BANNED' ? <CheckCircleOutlined /> : <StopOutlined />}
          loading={banMutation.isPending}
          onClick={() => banMutation.mutate()}
        >
          {user.status === 'BANNED' ? 'Unban' : 'Ban'}
        </Button>
        <Button type="primary" onClick={() => setGrantOpen(true)}>
          Grant Plan
        </Button>
      </Space>

      <Card style={{ marginBottom: 16 }}>
        <Descriptions
          title={
            <Space>
              <span>User Profile</span>
              <RoleBadge role={user.role} />
              <StatusBadge status={user.status} />
            </Space>
          }
          column={{ xs: 1, sm: 2, lg: 3 }}
          bordered
          size="small"
        >
          <Descriptions.Item label="ID">{user.id}</Descriptions.Item>
          <Descriptions.Item label="Telegram ID">{user.telegramId}</Descriptions.Item>
          <Descriptions.Item label="Username">{user.username ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="First Name">{user.firstName ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="Language">{user.languageCode}</Descriptions.Item>
          <Descriptions.Item label="Token Balance">{user.tokenBalance}</Descriptions.Item>
          <Descriptions.Item label="Monthly Token Grant">{user.tokenMonthlyGrantRef}</Descriptions.Item>
          <Descriptions.Item label="Low Threshold %">{user.tokenLowThresholdPct}</Descriptions.Item>
          <Descriptions.Item label="Max Controllers">{user.maxControllers}</Descriptions.Item>
          <Descriptions.Item label="Plan">
            {user.planCode ? <Tag color="blue">{user.planName}</Tag> : <Typography.Text type="secondary">No plan</Typography.Text>}
          </Descriptions.Item>
          <Descriptions.Item label="Sub Started">
            {user.subscriptionStartedAt ? dayjs(user.subscriptionStartedAt).format('DD.MM.YYYY HH:mm') : '—'}
          </Descriptions.Item>
          <Descriptions.Item label="Sub Expires">
            {user.subscriptionExpiresAt ? dayjs(user.subscriptionExpiresAt).format('DD.MM.YYYY HH:mm') : '—'}
          </Descriptions.Item>
          <Descriptions.Item label="Created">{dayjs(user.createdAt).format('DD.MM.YYYY HH:mm')}</Descriptions.Item>
          <Descriptions.Item label="Updated">{dayjs(user.updatedAt).format('DD.MM.YYYY HH:mm')}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Tabs
        defaultActiveKey="controllers"
        items={[
          {
            key: 'controllers',
            label: 'Controllers',
            children: (
              <Table<Controller>
                dataSource={userControllers}
                columns={ctrlColumns}
                rowKey="id"
                loading={ctrlLoading}
                size="small"
                pagination={{
                  current: ctrlPage + 1,
                  pageSize: 10,
                  total: ctrlData?.page?.totalElements ?? 0,
                  onChange: (p) => setCtrlPage(p - 1),
                  showSizeChanger: false,
                }}
              />
            ),
          },
          {
            key: 'audit',
            label: 'Audit Log',
            children: (
              <Table<AuditLog>
                dataSource={auditData?.content ?? []}
                columns={auditColumns}
                rowKey="id"
                loading={auditLoading}
                size="small"
                pagination={{
                  current: auditPage + 1,
                  pageSize: 10,
                  total: auditData?.totalElements ?? 0,
                  onChange: (p) => setAuditPage(p - 1),
                  showSizeChanger: false,
                }}
              />
            ),
          },
          {
            key: 'notifications',
            label: 'Notifications',
            children: (
              <Table<NotificationLog>
                dataSource={notifData?.content ?? []}
                columns={notifColumns}
                rowKey="id"
                loading={notifLoading}
                size="small"
                pagination={{
                  current: notifPage + 1,
                  pageSize: 10,
                  total: notifData?.totalElements ?? 0,
                  onChange: (p) => setNotifPage(p - 1),
                  showSizeChanger: false,
                }}
              />
            ),
          },
        ]}
      />

      <Modal
        title="Grant Subscription Plan"
        open={grantOpen}
        onCancel={() => {
          setGrantOpen(false);
          grantForm.resetFields();
        }}
        onOk={() => grantForm.submit()}
        okText="Grant"
        confirmLoading={grantMutation.isPending}
      >
        <Form<{ planCode: string }>
          form={grantForm}
          layout="vertical"
          onFinish={(values) => grantMutation.mutate(values.planCode)}
        >
          <Form.Item
            name="planCode"
            label="Select Plan"
            rules={[{ required: true, message: 'Please select a plan' }]}
          >
            <Select options={PLAN_OPTIONS} placeholder="Choose a plan" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
