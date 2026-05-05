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
  InputNumber,
  Space,
  Typography,
  Tag,
  Spin,
  App,
  DatePicker,
} from 'antd';
import {
  ArrowLeftOutlined,
  StopOutlined,
  CheckCircleOutlined,
  EditOutlined,
  SaveOutlined,
  CloseOutlined,
} from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useParams, useNavigate } from 'react-router-dom';
import dayjs from 'dayjs';
import { usersApi, subscriptionsApi } from '../../api/endpoints';
import { StatusBadge, RoleBadge } from '../../components/StatusBadge';
import type { AuditLog, NotificationLog, Controller, UserDetail } from '../../api/types';

const PLAN_OPTIONS = [
  { label: 'FREE', value: 'FREE' },
  { label: 'PRO', value: 'PRO' },
  { label: 'PREMIUM', value: 'PREMIUM' },
];

interface ProfileDraft {
  tokenBalance: number;
  tokenLowThresholdPct: number;
  tokenMonthlyGrantRef: number;
  startedAt: ReturnType<typeof dayjs> | null;
  expiresAt: ReturnType<typeof dayjs> | null;
}

export default function UserDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { notification } = App.useApp();
  const qc = useQueryClient();
  const [auditPage, setAuditPage] = useState(0);
  const [notifPage, setNotifPage] = useState(0);
  const [ctrlPage] = useState(0);
  const [grantOpen, setGrantOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [grantForm] = Form.useForm<{ planCode: string }>();
  const [editForm] = Form.useForm<ProfileDraft>();

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
    queryKey: ['user-controllers', id, ctrlPage],
    queryFn: () => usersApi.controllers(id!),
    enabled: !!id,
  });

  const refetchUser = () => qc.invalidateQueries({ queryKey: ['user', id] });

  const banMutation = useMutation({
    mutationFn: () =>
      user?.status === 'BANNED' ? usersApi.unban(id!) : usersApi.ban(id!),
    onSuccess: () => {
      notification.success({ message: 'Статус обновлён' });
      refetchUser();
      qc.invalidateQueries({ queryKey: ['users'] });
    },
    onError: (err: Error) => notification.error({ message: err.message }),
  });

  const grantMutation = useMutation({
    mutationFn: (planCode: string) => subscriptionsApi.grant(id!, { planCode }),
    onSuccess: () => {
      notification.success({ message: 'План выдан' });
      setGrantOpen(false);
      grantForm.resetFields();
      refetchUser();
    },
    onError: (err: Error) => notification.error({ message: err.message }),
  });

  const saveMutation = useMutation({
    mutationFn: async (values: ProfileDraft) => {
      await usersApi.updateProfile(id!, {
        tokenBalance: values.tokenBalance,
        tokenLowThresholdPct: values.tokenLowThresholdPct,
        tokenMonthlyGrantRef: values.tokenMonthlyGrantRef,
      });
      if (user?.subscriptionStartedAt || values.startedAt || values.expiresAt) {
        await usersApi.updateSubscriptionDates(id!, {
          startedAt: values.startedAt?.toISOString() ?? null,
          expiresAt: values.expiresAt?.toISOString() ?? null,
        });
      }
    },
    onSuccess: () => {
      notification.success({ message: 'Сохранено' });
      setEditing(false);
      refetchUser();
    },
    onError: (err: Error) => notification.error({ message: err.message }),
  });

  const startEditing = (u: UserDetail) => {
    editForm.setFieldsValue({
      tokenBalance: u.tokenBalance,
      tokenLowThresholdPct: u.tokenLowThresholdPct,
      tokenMonthlyGrantRef: u.tokenMonthlyGrantRef,
      startedAt: u.subscriptionStartedAt ? dayjs(u.subscriptionStartedAt) : null,
      expiresAt: u.subscriptionExpiresAt ? dayjs(u.subscriptionExpiresAt) : null,
    });
    setEditing(true);
  };

  const cancelEditing = () => {
    setEditing(false);
    editForm.resetFields();
  };

  if (userLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 64 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!user) return <Typography.Text type="danger">Пользователь не найден</Typography.Text>;

  const controllers: Controller[] = Array.isArray(ctrlData) ? ctrlData : [];

  const auditColumns = [
    { title: 'Действие', dataIndex: 'action', key: 'action' },
    { title: 'Сущность', dataIndex: 'entityType', key: 'entityType', render: (v: string | null) => v ?? '—' },
    { title: 'ID сущности', dataIndex: 'entityId', key: 'entityId', render: (v: string | null) => v ?? '—' },
    { title: 'IP', dataIndex: 'ipAddress', key: 'ip', render: (v: string | null) => v ?? '—' },
    {
      title: 'Дата',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (v: string) => dayjs(v).format('DD.MM.YYYY HH:mm'),
    },
  ];

  const notifColumns = [
    { title: 'Канал', dataIndex: 'channel', key: 'channel' },
    {
      title: 'Статус',
      dataIndex: 'status',
      key: 'status',
      render: (v: string) => (
        <Tag color={v === 'SENT' ? 'success' : v === 'FAILED' ? 'error' : 'default'}>{v}</Tag>
      ),
    },
    { title: 'Попыток', dataIndex: 'attempts', key: 'attempts', width: 90 },
    {
      title: 'Отправлено',
      dataIndex: 'sentAt',
      key: 'sentAt',
      render: (v: string | null) => (v ? dayjs(v).format('DD.MM.YYYY HH:mm') : '—'),
    },
  ];

  const ctrlColumns = [
    { title: 'Букмекер', dataIndex: 'bookmaker', key: 'bookmaker', render: (v: string) => <Tag>{v}</Tag> },
    { title: 'Название', dataIndex: 'title', key: 'title', render: (v: string | null) => v ?? '—' },
    {
      title: 'Статус',
      key: 'status',
      render: (_: unknown, c: Controller) => (
        <Space size={4}>
          <Tag color={c.isActive ? 'success' : 'default'}>{c.isActive ? 'Active' : 'Inactive'}</Tag>
          {c.isMuted && <Tag color="warning">Muted</Tag>}
        </Space>
      ),
    },
    { title: 'Событий', dataIndex: 'detectedEventsCount', key: 'events' },
    {
      title: 'Последнее событие',
      dataIndex: 'lastEventAt',
      key: 'lastEventAt',
      render: (v: string | null) => (v ? dayjs(v).format('DD.MM.YYYY HH:mm') : '—'),
    },
  ];

  return (
    <>
      <Space style={{ marginBottom: 16 }} wrap>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/users')}>
          Пользователи
        </Button>
        <Button
          danger={user.status !== 'BANNED'}
          icon={user.status === 'BANNED' ? <CheckCircleOutlined /> : <StopOutlined />}
          loading={banMutation.isPending}
          onClick={() => banMutation.mutate()}
        >
          {user.status === 'BANNED' ? 'Разбан' : 'Бан'}
        </Button>
        <Button type="primary" onClick={() => setGrantOpen(true)}>
          Выдать план
        </Button>
        {!editing ? (
          <Button icon={<EditOutlined />} onClick={() => startEditing(user)}>
            Изменить профиль
          </Button>
        ) : (
          <>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={saveMutation.isPending}
              onClick={() => editForm.submit()}
            >
              Сохранить
            </Button>
            <Button icon={<CloseOutlined />} onClick={cancelEditing}>
              Отменить
            </Button>
          </>
        )}
      </Space>

      <Card style={{ marginBottom: 16 }}>
        {editing ? (
          <Form<ProfileDraft>
            form={editForm}
            layout="vertical"
            onFinish={(values) => saveMutation.mutate(values)}
          >
            <Typography.Title level={5} style={{ marginBottom: 16 }}>
              <Space>
                <span>Профиль пользователя</span>
                <RoleBadge role={user.role} />
                <StatusBadge status={user.status} />
              </Space>
            </Typography.Title>

            <Descriptions column={{ xs: 1, sm: 2, lg: 3 }} size="small" bordered>
              <Descriptions.Item label="ID">
                <Typography.Text code style={{ fontSize: 11 }}>{user.id}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="Telegram ID">{user.telegramId}</Descriptions.Item>
              <Descriptions.Item label="Username">{user.username ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="Имя">{user.firstName ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="Язык">{user.languageCode}</Descriptions.Item>
              <Descriptions.Item label="Макс. контроллеров">
                <Typography.Text title="Ограничение по плану подписки">{user.maxControllers}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="Баланс токенов">
                <Form.Item name="tokenBalance" style={{ margin: 0 }} rules={[{ required: true }]}>
                  <InputNumber min={0} style={{ width: 120 }} />
                </Form.Item>
              </Descriptions.Item>
              <Descriptions.Item label="Ежемесячный грант токенов">
                <Form.Item
                  name="tokenMonthlyGrantRef"
                  style={{ margin: 0 }}
                  tooltip="Сколько токенов начисляется пользователю автоматически каждый месяц"
                  rules={[{ required: true }]}
                >
                  <InputNumber min={0} style={{ width: 120 }} />
                </Form.Item>
              </Descriptions.Item>
              <Descriptions.Item label="Порог низкого баланса %">
                <Form.Item
                  name="tokenLowThresholdPct"
                  style={{ margin: 0 }}
                  tooltip="Бот предупредит когда баланс упадёт ниже этого порога"
                  rules={[{ required: true }]}
                >
                  <InputNumber min={0} max={100} addonAfter="%" style={{ width: 120 }} />
                </Form.Item>
              </Descriptions.Item>
              <Descriptions.Item label="План">
                {user.planCode
                  ? <Tag color={user.planCode === 'PREMIUM' ? 'gold' : user.planCode === 'PRO' ? 'blue' : 'default'}>{user.planName}</Tag>
                  : <Typography.Text type="secondary">Нет плана</Typography.Text>}
              </Descriptions.Item>
              <Descriptions.Item label="Начало подписки">
                <Form.Item name="startedAt" style={{ margin: 0 }}>
                  <DatePicker showTime format="DD.MM.YYYY HH:mm" style={{ width: 180 }} />
                </Form.Item>
              </Descriptions.Item>
              <Descriptions.Item label="Окончание подписки">
                <Form.Item name="expiresAt" style={{ margin: 0 }}>
                  <DatePicker showTime format="DD.MM.YYYY HH:mm" style={{ width: 180 }} allowClear />
                </Form.Item>
              </Descriptions.Item>
            </Descriptions>
          </Form>
        ) : (
          <Descriptions
            title={
              <Space>
                <span>Профиль пользователя</span>
                <RoleBadge role={user.role} />
                <StatusBadge status={user.status} />
              </Space>
            }
            column={{ xs: 1, sm: 2, lg: 3 }}
            bordered
            size="small"
          >
            <Descriptions.Item label="ID">
              <Typography.Text code style={{ fontSize: 11 }}>{user.id}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Telegram ID">{user.telegramId}</Descriptions.Item>
            <Descriptions.Item label="Username">{user.username ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="Имя">{user.firstName ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="Язык">{user.languageCode}</Descriptions.Item>
            <Descriptions.Item label="Баланс токенов">{user.tokenBalance}</Descriptions.Item>
            <Descriptions.Item label="Ежемесячный грант токенов">
              <Typography.Text title="Сколько токенов начисляется автоматически каждый месяц">
                {user.tokenMonthlyGrantRef}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Порог низкого баланса %">
              <Typography.Text title="Бот предупредит когда баланс упадёт ниже этого порога">
                {user.tokenLowThresholdPct}%
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Макс. контроллеров">
              <Typography.Text title="Ограничение по плану подписки">
                {user.maxControllers}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="План">
              {user.planCode
                ? <Tag color={user.planCode === 'PREMIUM' ? 'gold' : user.planCode === 'PRO' ? 'blue' : 'default'}>{user.planName}</Tag>
                : <Typography.Text type="secondary">Нет плана</Typography.Text>}
            </Descriptions.Item>
            <Descriptions.Item label="Начало подписки">
              {user.subscriptionStartedAt ? dayjs(user.subscriptionStartedAt).format('DD.MM.YYYY HH:mm') : '—'}
            </Descriptions.Item>
            <Descriptions.Item label="Окончание подписки">
              {user.subscriptionExpiresAt
                ? <Tag color={dayjs(user.subscriptionExpiresAt).isBefore(dayjs()) ? 'default' : 'processing'}>
                    {dayjs(user.subscriptionExpiresAt).format('DD.MM.YYYY HH:mm')}
                  </Tag>
                : <Typography.Text type="secondary">Бессрочно</Typography.Text>}
            </Descriptions.Item>
            <Descriptions.Item label="Регистрация">{dayjs(user.createdAt).format('DD.MM.YYYY HH:mm')}</Descriptions.Item>
            <Descriptions.Item label="Обновлён">{dayjs(user.updatedAt).format('DD.MM.YYYY HH:mm')}</Descriptions.Item>
          </Descriptions>
        )}
      </Card>

      <Tabs
        defaultActiveKey="controllers"
        items={[
          {
            key: 'controllers',
            label: `Контроллеры (${controllers.length})`,
            children: (
              <Table<Controller>
                dataSource={controllers}
                columns={ctrlColumns}
                rowKey="id"
                loading={ctrlLoading}
                size="small"
                onRow={(c) => ({ onClick: () => navigate(`/controllers/${c.id}`), style: { cursor: 'pointer' } })}
                pagination={{ pageSize: 10, showSizeChanger: false }}
              />
            ),
          },
          {
            key: 'audit',
            label: 'Аудит-лог',
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
            label: 'Уведомления',
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

      {/* Grant plan */}
      <Modal
        title="Выдать план"
        open={grantOpen}
        onCancel={() => { setGrantOpen(false); grantForm.resetFields(); }}
        onOk={() => grantForm.submit()}
        okText="Выдать"
        confirmLoading={grantMutation.isPending}
      >
        <Form<{ planCode: string }>
          form={grantForm}
          layout="vertical"
          onFinish={(values) => grantMutation.mutate(values.planCode)}
        >
          <Form.Item name="planCode" label="Выберите план" rules={[{ required: true }]}>
            <Select options={PLAN_OPTIONS} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
