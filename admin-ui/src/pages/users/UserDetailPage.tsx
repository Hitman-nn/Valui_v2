import { useState } from 'react';
import {
  Card,
  Descriptions,
  Tabs,
  Table,
  Button,
  Form,
  InputNumber,
  Space,
  Typography,
  Tag,
  Spin,
  App,
  Modal,
  DatePicker,
  Tooltip,
  Row,
  Col,
} from 'antd';
import {
  ArrowLeftOutlined,
  StopOutlined,
  CheckCircleOutlined,
  EditOutlined,
  SaveOutlined,
  CloseOutlined,
  ReloadOutlined,
  DeleteOutlined,
  ArrowUpOutlined,
  ArrowDownOutlined,
} from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useParams, useNavigate } from 'react-router-dom';
import dayjs from 'dayjs';
import { usersApi } from '../../api/endpoints';
import { StatusBadge, RoleBadge } from '../../components/StatusBadge';
import StatCard from '../../components/StatCard';
import type { AuditLog, NotificationLog, Controller, UserDetail, TokenHistoryEntry } from '../../api/types';

const REASON_LABELS: Record<string, string> = {
  PLAN_GRANT:                         'Начальный грант',
  MONTHLY_GRANT:                      'Ежемес. грант',
  TOPUP:                              'Пополнение',
  ADMIN_GRANT:                        'Админ: начисление',
  ADMIN_DEDUCT:                       'Админ: списание',
  CONTROLLER_BK_CHARGE:               'Букмекер-слот',
  MONTHLY_BK_CHARGE:                  'Ежемес. букмекер',
  FILTER_CHARGE:                      'Фильтр',
  MONTHLY_FILTER_CHARGE:              'Ежемес. фильтр',
  CONTROLLER_FILTER_CHARGE:           'Фильтр контроллера',
  MONTHLY_CONTROLLER_FILTER_CHARGE:   'Ежемес. фильтр контроллера',
  NOTIFICATION_SENT:                  'Уведомление',
};

const historyColumns = [
  {
    title: 'Дата',
    dataIndex: 'date',
    key: 'date',
    width: 90,
    render: (v: string) => dayjs(v).format('DD.MM.YYYY'),
  },
  {
    title: 'Операция',
    dataIndex: 'reasonCode',
    key: 'reasonCode',
    render: (v: string) => REASON_LABELS[v] ?? v,
  },
  {
    title: 'Изменение',
    dataIndex: 'totalDelta',
    key: 'totalDelta',
    width: 110,
    render: (v: number) => (
      <Typography.Text strong style={{ color: v >= 0 ? '#52c41a' : '#ff4d4f' }}>
        {v >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />} {Math.abs(v)}
      </Typography.Text>
    ),
  },
  {
    title: 'Операций',
    dataIndex: 'count',
    key: 'count',
    width: 90,
  },
];

interface ProfileDraft {
  tokenBalance: number;
  tokenLowThreshold: number | null;
  tokenMonthlyGrantRef: number;
}

export default function UserDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { notification } = App.useApp();
  const qc = useQueryClient();
  const [auditPage, setAuditPage] = useState(0);
  const [notifPage, setNotifPage] = useState(0);
  const [activeTab, setActiveTab] = useState('controllers');
  const [editing, setEditing] = useState(false);
  const [editForm] = Form.useForm<ProfileDraft>();
  const [resetModalOpen, setResetModalOpen] = useState(false);
  const [resetDate, setResetDate] = useState<Dayjs | null>(null);

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
    queryKey: ['user-controllers', id],
    queryFn: () => usersApi.controllers(id!),
    enabled: !!id,
  });

  const { data: tokenStatsData, isLoading: tokenStatsLoading } = useQuery({
    queryKey: ['user-token-stats', id],
    queryFn: () => usersApi.tokenStats(id!),
    enabled: !!id && activeTab === 'tokens',
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

  const saveMutation = useMutation({
    mutationFn: (values: ProfileDraft) =>
      usersApi.updateProfile(id!, {
        tokenBalance: values.tokenBalance,
        tokenLowThreshold: values.tokenLowThreshold,
        tokenMonthlyGrantRef: values.tokenMonthlyGrantRef,
      }),
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
      tokenLowThreshold: u.tokenLowThreshold,
      tokenMonthlyGrantRef: u.tokenMonthlyGrantRef,
    });
    setEditing(true);
  };

  const cancelEditing = () => {
    setEditing(false);
    editForm.resetFields();
  };

  const resetStatsMutation = useMutation({
    mutationFn: (isoDate?: string) => usersApi.resetTokenStats(id!, isoDate),
    onSuccess: () => {
      notification.success({ message: 'Статистика сброшена' });
      setResetModalOpen(false);
      setResetDate(null);
      refetchUser();
    },
    onError: (err: Error) => notification.error({ message: err.message }),
  });

  const clearResetMutation = useMutation({
    mutationFn: () => usersApi.clearTokenStatsReset(id!),
    onSuccess: () => {
      notification.success({ message: 'Граница статистики очищена' });
      refetchUser();
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
        {!editing && (
          <Tooltip title="Задать дату, начиная с которой отображается статистика токенов">
            <Button icon={<ReloadOutlined />} onClick={() => setResetModalOpen(true)}>
              Сбросить статистику
            </Button>
          </Tooltip>
        )}
        {!editing && user.tokenStatsResetAt && (
          <Tooltip title="Очистить границу — показывать всю историю">
            <Button
              danger icon={<DeleteOutlined />}
              loading={clearResetMutation.isPending}
              onClick={() => clearResetMutation.mutate()}
            >
              Очистить сброс
            </Button>
          </Tooltip>
        )}
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
              <Descriptions.Item label="Порог уведомления (токены)">
                <Form.Item
                  name="tokenLowThreshold"
                  style={{ margin: 0 }}
                  tooltip="Последний уведомлённый порог: 100 (инфо), 50 (предупреждение), 10 (критический). null — сбросить"
                >
                  <InputNumber min={0} placeholder="null = сброс" style={{ width: 160 }} />
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
            <Descriptions.Item label="Баланс токенов">
              <Typography.Text strong style={{ color: user.tokenBalance > 0 ? '#52c41a' : '#ff4d4f' }}>
                {user.tokenBalance}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Ежемесячный грант токенов">
              <Typography.Text title="Сколько токенов начисляется автоматически каждый месяц">
                {user.tokenMonthlyGrantRef}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Порог уведомления (токены)">
              <Typography.Text title="Последний уведомлённый порог: 100 / 50 / 10 или null">
                {user.tokenLowThreshold ?? '—'}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Статистика с">
              {user.tokenStatsResetAt ? (
                <Tag color="orange">{dayjs(user.tokenStatsResetAt).format('DD.MM.YYYY HH:mm')}</Tag>
              ) : (
                <Typography.Text type="secondary">вся история</Typography.Text>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="Регистрация">{dayjs(user.createdAt).format('DD.MM.YYYY HH:mm')}</Descriptions.Item>
            <Descriptions.Item label="Обновлён">{dayjs(user.updatedAt).format('DD.MM.YYYY HH:mm')}</Descriptions.Item>
          </Descriptions>
        )}
      </Card>

      <Modal
        title="Сбросить статистику токенов"
        open={resetModalOpen}
        onCancel={() => { setResetModalOpen(false); setResetDate(null); }}
        onOk={() => resetStatsMutation.mutate(resetDate ? resetDate.toISOString() : undefined)}
        okText="Применить"
        cancelText="Отмена"
        confirmLoading={resetStatsMutation.isPending}
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <Typography.Text>
            История и статистика токенов будут отображаться только начиная с выбранной даты.
            Данные <Typography.Text strong>не удаляются</Typography.Text> — граница в любой момент
            может быть очищена.
          </Typography.Text>
          <DatePicker
            showTime
            value={resetDate}
            onChange={(d) => setResetDate(d)}
            placeholder="Оставьте пустым — сбросить на сейчас"
            style={{ width: '100%' }}
            format="DD.MM.YYYY HH:mm"
          />
        </Space>
      </Modal>

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
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
          {
            key: 'tokens',
            label: 'Токены',
            children: (
              <Space direction="vertical" style={{ width: '100%' }} size="large">
                <Row gutter={16}>
                  <Col xs={24} sm={8}>
                    <StatCard
                      title="Потрачено за месяц"
                      value={tokenStatsData?.spentThisMonth ?? 0}
                      suffix="🪙"
                      loading={tokenStatsLoading}
                      color="#ff4d4f"
                    />
                  </Col>
                  <Col xs={24} sm={8}>
                    <StatCard
                      title="Среднее в месяц"
                      value={tokenStatsData?.avgPerMonth ?? 0}
                      suffix="🪙"
                      loading={tokenStatsLoading}
                    />
                  </Col>
                  <Col xs={24} sm={8}>
                    <StatCard
                      title="Потрачено всего"
                      value={tokenStatsData?.spentAllTime ?? 0}
                      suffix="🪙"
                      loading={tokenStatsLoading}
                    />
                  </Col>
                </Row>
                <Table<TokenHistoryEntry>
                  dataSource={tokenStatsData?.recentHistory ?? []}
                  columns={historyColumns}
                  rowKey={(r) => `${r.reasonCode}-${r.date}`}
                  loading={tokenStatsLoading}
                  size="small"
                  pagination={false}
                  title={() => (
                    <Typography.Text strong>
                      История операций{' '}
                      {user.tokenStatsResetAt && (
                        <Tag color="orange">
                          с {dayjs(user.tokenStatsResetAt).format('DD.MM.YYYY')}
                        </Tag>
                      )}
                    </Typography.Text>
                  )}
                />
              </Space>
            ),
          },
        ]}
      />
    </>
  );
}
