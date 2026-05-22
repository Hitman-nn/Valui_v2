import { useState } from 'react';
import {
  Table,
  InputNumber,
  Button,
  Space,
  Typography,
  App,
  Tooltip,
  Tag,
  Input,
  Divider,
} from 'antd';
import { EditOutlined, CheckOutlined, CloseOutlined, SearchOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { tokenCostsApi, usersApi } from '../../api/endpoints';
import type { TokenActionCost, UserSummary } from '../../api/types';

const { Title, Paragraph, Text } = Typography;

const ACTION_LABELS: Record<string, string> = {
  CONTROLLER_BK_MONTHLY:     'БК-слот (ежемесячно)',
  FILTER_MONTHLY:            'Глобальный фильтр (ежемесячно)',
  CONTROLLER_FILTER_MONTHLY: 'Фильтр на контроллере (ежемесячно)',
  NOTIFICATION_SENT:         'Отправка уведомления',
};

// ─── Token Costs section ──────────────────────────────────────────────────────

function CostsTable() {
  const { notification } = App.useApp();
  const qc = useQueryClient();

  const [editingCode, setEditingCode] = useState<string | null>(null);
  const [editValue, setEditValue] = useState<number>(0);

  const { data = [], isLoading } = useQuery({
    queryKey: ['token-costs'],
    queryFn: tokenCostsApi.list,
  });

  const mutation = useMutation({
    mutationFn: ({ actionCode, costTokens }: { actionCode: string; costTokens: number }) =>
      tokenCostsApi.update(actionCode, { costTokens }),
    onSuccess: () => {
      notification.success({ message: 'Сохранено' });
      qc.invalidateQueries({ queryKey: ['token-costs'] });
      setEditingCode(null);
    },
    onError: (err: Error) => notification.error({ message: err.message }),
  });

  const columns = [
    {
      title: 'Код операции',
      dataIndex: 'actionCode',
      key: 'actionCode',
      width: 280,
      render: (code: string) => (
        <Text code style={{ fontSize: 12 }}>{code}</Text>
      ),
    },
    {
      title: 'Описание',
      dataIndex: 'actionCode',
      key: 'label',
      render: (code: string, record: TokenActionCost) =>
        ACTION_LABELS[code] ?? record.description ?? <Text type="secondary">—</Text>,
    },
    {
      title: 'Стоимость, токенов',
      dataIndex: 'costTokens',
      key: 'costTokens',
      width: 200,
      render: (_: number, record: TokenActionCost) => {
        if (editingCode === record.actionCode) {
          return (
            <InputNumber
              min={0}
              value={editValue}
              onChange={(v) => setEditValue(v ?? 0)}
              onPressEnter={() => mutation.mutate({ actionCode: record.actionCode, costTokens: editValue })}
              autoFocus
              style={{ width: 100 }}
            />
          );
        }
        return <Tag color={record.costTokens === 0 ? 'default' : 'blue'}>{record.costTokens} 🪙</Tag>;
      },
    },
    {
      title: '',
      key: 'actions',
      width: 140,
      render: (_: unknown, record: TokenActionCost) => {
        if (editingCode === record.actionCode) {
          return (
            <Space>
              <Button
                type="primary" size="small" icon={<CheckOutlined />}
                loading={mutation.isPending}
                onClick={() => mutation.mutate({ actionCode: record.actionCode, costTokens: editValue })}
              >Сохранить</Button>
              <Button size="small" icon={<CloseOutlined />} onClick={() => setEditingCode(null)}>Отмена</Button>
            </Space>
          );
        }
        return (
          <Tooltip title="Изменить стоимость">
            <Button type="text" size="small" icon={<EditOutlined />}
              onClick={() => { setEditingCode(record.actionCode); setEditValue(record.costTokens); }} />
          </Tooltip>
        );
      },
    },
  ];

  return (
    <Table<TokenActionCost>
      dataSource={data} columns={columns} rowKey="actionCode"
      loading={isLoading} pagination={false} size="middle"
    />
  );
}

// ─── User Grants section ──────────────────────────────────────────────────────

function UserGrantsTable() {
  const { notification } = App.useApp();
  const qc = useQueryClient();

  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [editingId, setEditingId]   = useState<string | null>(null);
  const [editValue, setEditValue]   = useState<number>(0);

  const { data, isLoading } = useQuery({
    queryKey: ['users-grants', page, search],
    queryFn: () => usersApi.list({ page, size: 20, sort: 'createdAt,desc', ...(search ? { search } : {}) }),
  });

  const users: UserSummary[] = data?._embedded?.users ?? [];
  const total = data?.page?.totalElements ?? 0;

  const mutation = useMutation({
    mutationFn: ({ id, grant }: { id: string; grant: number }) =>
      usersApi.updateProfile(id, { tokenMonthlyGrantRef: grant }),
    onSuccess: () => {
      notification.success({ message: 'Грант обновлён' });
      qc.invalidateQueries({ queryKey: ['users-grants'] });
      setEditingId(null);
    },
    onError: (err: Error) => notification.error({ message: err.message }),
  });

  const columns = [
    {
      title: 'Telegram ID',
      dataIndex: 'telegramId',
      key: 'telegramId',
      width: 120,
      render: (v: number) => <Text type="secondary">{v}</Text>,
    },
    {
      title: 'Пользователь',
      key: 'name',
      render: (_: unknown, u: UserSummary) => (
        <Space direction="vertical" size={0}>
          <Text>{u.firstName ?? '—'}</Text>
          {u.username && <Text type="secondary" style={{ fontSize: 12 }}>@{u.username}</Text>}
        </Space>
      ),
    },
    {
      title: 'Баланс',
      dataIndex: 'tokenBalance',
      key: 'tokenBalance',
      width: 110,
      render: (v: number) => <Tag color="blue">{v} 🪙</Tag>,
    },
    {
      title: 'Грант/мес',
      dataIndex: 'tokenMonthlyGrantRef',
      key: 'tokenMonthlyGrantRef',
      width: 180,
      render: (_: number, u: UserSummary) => {
        if (editingId === u.id) {
          return (
            <InputNumber
              min={0}
              value={editValue}
              onChange={(v) => setEditValue(v ?? 0)}
              onPressEnter={() => mutation.mutate({ id: u.id, grant: editValue })}
              autoFocus
              style={{ width: 100 }}
            />
          );
        }
        return (
          <Tag color={u.tokenMonthlyGrantRef === 0 ? 'default' : 'green'}>
            {u.tokenMonthlyGrantRef} 🪙/мес
          </Tag>
        );
      },
    },
    {
      title: '',
      key: 'actions',
      width: 140,
      render: (_: unknown, u: UserSummary) => {
        if (editingId === u.id) {
          return (
            <Space>
              <Button
                type="primary" size="small" icon={<CheckOutlined />}
                loading={mutation.isPending}
                onClick={() => mutation.mutate({ id: u.id, grant: editValue })}
              >Сохранить</Button>
              <Button size="small" icon={<CloseOutlined />} onClick={() => setEditingId(null)}>Отмена</Button>
            </Space>
          );
        }
        return (
          <Tooltip title="Изменить грант">
            <Button type="text" size="small" icon={<EditOutlined />}
              onClick={() => { setEditingId(u.id); setEditValue(u.tokenMonthlyGrantRef); }} />
          </Tooltip>
        );
      },
    },
  ];

  return (
    <>
      <Input
        placeholder="Поиск по имени, username или Telegram ID"
        prefix={<SearchOutlined />}
        value={search}
        onChange={(e) => { setSearch(e.target.value); setPage(0); }}
        allowClear
        style={{ width: 340, marginBottom: 16 }}
      />
      <Table<UserSummary>
        dataSource={users} columns={columns} rowKey="id"
        loading={isLoading} size="middle"
        pagination={{
          current: page + 1,
          pageSize: 20,
          total,
          showTotal: (t) => `Всего ${t}`,
          onChange: (p) => setPage(p - 1),
          showSizeChanger: false,
        }}
      />
    </>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function TokenCostsPage() {
  return (
    <>
      <Title level={5} style={{ marginTop: 0 }}>Стоимость операций</Title>
      <Paragraph type="secondary" style={{ marginBottom: 16 }}>
        Значения берутся из БД в реальном времени — изменения вступают в силу немедленно.
      </Paragraph>
      <CostsTable />

      <Divider />

      <Title level={5}>Ежемесячный грант токенов</Title>
      <Paragraph type="secondary" style={{ marginBottom: 16 }}>
        Начисляется каждому пользователю 1-го числа месяца автоматически.
      </Paragraph>
      <UserGrantsTable />
    </>
  );
}
