import { useState } from 'react';
import {
  Table,
  Input,
  Select,
  Space,
  Button,
  App,
  Typography,
  Tag,
  TablePaginationConfig,
} from 'antd';
import type { SorterResult } from 'antd/es/table/interface';
import { SearchOutlined, StopOutlined, CheckCircleOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import dayjs from 'dayjs';
import { usersApi } from '../../api/endpoints';
import { StatusBadge, RoleBadge } from '../../components/StatusBadge';
import type { UserSummary } from '../../api/types';

function planColor(code: string | null): string {
  switch (code?.toUpperCase()) {
    case 'PREMIUM': return 'gold';
    case 'PRO':     return 'blue';
    default:        return 'default';
  }
}

function subDaysLabel(expiresAt: string | null): React.ReactNode {
  if (!expiresAt) return <Typography.Text type="secondary">—</Typography.Text>;
  const days = dayjs(expiresAt).diff(dayjs(), 'day');
  if (days < 0) return <Tag color="default">Истёк</Tag>;
  if (days === 0) return <Tag color="warning">Сегодня</Tag>;
  if (days <= 7)  return <Tag color="warning">{days} д.</Tag>;
  return <Tag color="processing">{days} д.</Tag>;
}

export default function UsersPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<string>('createdAt,desc');
  const statusFilter = (searchParams.get('status') as 'ACTIVE' | 'BANNED' | null) ?? 'ALL';
  const navigate = useNavigate();
  const { notification } = App.useApp();
  const qc = useQueryClient();

  const setStatusFilter = (value: 'ALL' | 'ACTIVE' | 'BANNED') => {
    setPage(0);
    if (value === 'ALL') setSearchParams({});
    else setSearchParams({ status: value });
  };

  const { data, isLoading } = useQuery({
    queryKey: ['users', page, statusFilter, sort],
    queryFn: () => usersApi.list({
      page,
      size: 20,
      sort,
      ...(statusFilter !== 'ALL' ? { status: statusFilter } : {}),
    }),
  });

  const users: UserSummary[] = data?._embedded?.users ?? [];
  const total = data?.page?.totalElements ?? 0;

  const filteredUsers = users.filter((u) =>
    !search ||
    u.username?.toLowerCase().includes(search.toLowerCase()) ||
    u.firstName?.toLowerCase().includes(search.toLowerCase()) ||
    String(u.telegramId).includes(search),
  );

  const banMutation = useMutation({
    mutationFn: (user: UserSummary) =>
      user.status === 'BANNED' ? usersApi.unban(user.id) : usersApi.ban(user.id),
    onSuccess: () => {
      notification.success({ message: 'Статус обновлён' });
      qc.invalidateQueries({ queryKey: ['users'] });
    },
    onError: (err: Error) => notification.error({ message: err.message }),
  });

  const handleTableChange = (
    pagination: TablePaginationConfig,
    _: unknown,
    sorter: SorterResult<UserSummary> | SorterResult<UserSummary>[],
  ) => {
    setPage((pagination.current ?? 1) - 1);
    const s = Array.isArray(sorter) ? sorter[0] : sorter;
    if (s?.columnKey && s.order) {
      const dir = s.order === 'ascend' ? 'asc' : 'desc';
      setSort(`${String(s.columnKey)},${dir}`);
    }
  };

  const columns = [
    {
      title: 'Telegram ID',
      dataIndex: 'telegramId',
      key: 'telegramId',
      width: 120,
      sorter: true,
    },
    {
      title: 'Имя',
      dataIndex: 'firstName',
      key: 'firstName',
      width: 120,
      sorter: true,
      render: (v: string | null) => v ?? <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: 'Username',
      dataIndex: 'username',
      key: 'username',
      sorter: true,
      render: (v: string | null) => v ?? <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: 'Роль',
      dataIndex: 'role',
      key: 'role',
      width: 90,
      sorter: true,
      render: (role: UserSummary['role']) => <RoleBadge role={role} />,
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      sorter: true,
      render: (status: UserSummary['status']) => <StatusBadge status={status} />,
    },
    {
      title: 'План',
      dataIndex: 'planCode',
      key: 'planCode',
      width: 90,
      sorter: true,
      render: (v: string | null) => v
        ? <Tag color={planColor(v)}>{v}</Tag>
        : <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: 'Подписка',
      dataIndex: 'subscriptionExpiresAt',
      key: 'subscriptionExpiresAt',
      width: 110,
      sorter: true,
      render: (v: string | null) => subDaysLabel(v),
    },
    {
      title: 'Контроллеры',
      dataIndex: 'controllersCount',
      key: 'controllersCount',
      width: 110,
      align: 'right' as const,
      sorter: true,
    },
    {
      title: 'Токены',
      dataIndex: 'tokenBalance',
      key: 'tokenBalance',
      width: 90,
      align: 'right' as const,
      sorter: true,
    },
    {
      title: '',
      key: 'actions',
      width: 100,
      render: (_: unknown, record: UserSummary) => (
        <Button
          size="small"
          danger={record.status !== 'BANNED'}
          type={record.status === 'BANNED' ? 'default' : 'text'}
          icon={record.status === 'BANNED' ? <CheckCircleOutlined /> : <StopOutlined />}
          loading={banMutation.isPending}
          onClick={(e) => { e.stopPropagation(); banMutation.mutate(record); }}
        >
          {record.status === 'BANNED' ? 'Разбан' : 'Бан'}
        </Button>
      ),
    },
  ];

  return (
    <>
      <Space style={{ marginBottom: 16, flexWrap: 'wrap' }} size="middle">
        <Input
          placeholder="Поиск по имени, username или Telegram ID"
          prefix={<SearchOutlined />}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ width: 320 }}
          allowClear
        />
        <Select
          value={statusFilter}
          onChange={setStatusFilter}
          style={{ width: 150 }}
          options={[
            { label: 'Все статусы', value: 'ALL' },
            { label: 'Активные',    value: 'ACTIVE' },
            { label: 'Забаненные',  value: 'BANNED' },
          ]}
        />
      </Space>

      <Table<UserSummary>
        dataSource={filteredUsers}
        columns={columns}
        rowKey="id"
        loading={isLoading}
        pagination={{
          current: page + 1,
          pageSize: 20,
          total,
          showSizeChanger: false,
          showTotal: (t) => `Всего ${t}`,
        }}
        onChange={handleTableChange}
        onRow={(record) => ({
          onClick: () => navigate(`/users/${record.id}`),
          style: { cursor: 'pointer' },
        })}
        size="middle"
      />
    </>
  );
}
