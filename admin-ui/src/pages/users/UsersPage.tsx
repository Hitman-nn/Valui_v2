import { useState } from 'react';
import {
  Table,
  Input,
  Select,
  Space,
  Button,
  Modal,
  Form,
  App,
  Typography,
  TablePaginationConfig,
} from 'antd';
import { SearchOutlined, StopOutlined, CheckCircleOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import dayjs from 'dayjs';
import { usersApi, subscriptionsApi } from '../../api/endpoints';
import { StatusBadge, RoleBadge } from '../../components/StatusBadge';
import type { UserSummary } from '../../api/types';

const PLAN_OPTIONS = [
  { label: 'FREE', value: 'FREE' },
  { label: 'PRO', value: 'PRO' },
  { label: 'PREMIUM', value: 'PREMIUM' },
];

export default function UsersPage() {
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'BANNED'>('ALL');
  const [grantUserId, setGrantUserId] = useState<string | null>(null);
  const [grantForm] = Form.useForm<{ planCode: string }>();
  const navigate = useNavigate();
  const { notification } = App.useApp();
  const qc = useQueryClient();

  const { data, isLoading } = useQuery({
    queryKey: ['users', page],
    queryFn: () => usersApi.list({ page, size: 20, sort: 'createdAt' }),
  });

  const users: UserSummary[] = data?._embedded?.users ?? [];
  const total = data?.page?.totalElements ?? 0;

  const filteredUsers = users.filter((u) => {
    const matchesSearch =
      !search ||
      u.username?.toLowerCase().includes(search.toLowerCase()) ||
      String(u.telegramId).includes(search);
    const matchesStatus = statusFilter === 'ALL' || u.status === statusFilter;
    return matchesSearch && matchesStatus;
  });

  const banMutation = useMutation({
    mutationFn: (user: UserSummary) =>
      user.status === 'BANNED' ? usersApi.unban(user.id) : usersApi.ban(user.id),
    onSuccess: () => {
      notification.success({ message: 'User status updated' });
      qc.invalidateQueries({ queryKey: ['users'] });
    },
    onError: (err: Error) => {
      notification.error({ message: 'Failed to update status', description: err.message });
    },
  });

  const grantMutation = useMutation({
    mutationFn: ({ userId, planCode }: { userId: string; planCode: string }) =>
      subscriptionsApi.grant(userId, { planCode }),
    onSuccess: () => {
      notification.success({ message: 'Plan granted successfully' });
      setGrantUserId(null);
      grantForm.resetFields();
      qc.invalidateQueries({ queryKey: ['users'] });
    },
    onError: (err: Error) => {
      notification.error({ message: 'Failed to grant plan', description: err.message });
    },
  });

  const handleTableChange = (pagination: TablePaginationConfig) => {
    setPage((pagination.current ?? 1) - 1);
  };

  const columns = [
    {
      title: 'Telegram ID',
      dataIndex: 'telegramId',
      key: 'telegramId',
      width: 130,
    },
    {
      title: 'Username',
      dataIndex: 'username',
      key: 'username',
      render: (v: string | null) => v ?? <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: 'Role',
      dataIndex: 'role',
      key: 'role',
      width: 90,
      render: (role: UserSummary['role']) => <RoleBadge role={role} />,
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: UserSummary['status']) => <StatusBadge status={status} />,
    },
    {
      title: 'Tokens',
      dataIndex: 'tokenBalance',
      key: 'tokenBalance',
      width: 100,
      align: 'right' as const,
    },
    {
      title: 'Created',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 150,
      render: (v: string) => dayjs(v).format('DD.MM.YYYY HH:mm'),
    },
    {
      title: 'Actions',
      key: 'actions',
      width: 180,
      render: (_: unknown, record: UserSummary) => (
        <Space size="small">
          <Button
            size="small"
            danger={record.status !== 'BANNED'}
            type={record.status === 'BANNED' ? 'default' : 'text'}
            icon={record.status === 'BANNED' ? <CheckCircleOutlined /> : <StopOutlined />}
            loading={banMutation.isPending}
            onClick={(e) => {
              e.stopPropagation();
              banMutation.mutate(record);
            }}
          >
            {record.status === 'BANNED' ? 'Unban' : 'Ban'}
          </Button>
          <Button
            size="small"
            type="link"
            onClick={(e) => {
              e.stopPropagation();
              setGrantUserId(record.id);
            }}
          >
            Grant Plan
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <>
      <Space style={{ marginBottom: 16, flexWrap: 'wrap' }} size="middle">
        <Input
          placeholder="Search by username or Telegram ID"
          prefix={<SearchOutlined />}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ width: 300 }}
          allowClear
        />
        <Select
          value={statusFilter}
          onChange={setStatusFilter}
          style={{ width: 140 }}
          options={[
            { label: 'All Statuses', value: 'ALL' },
            { label: 'Active', value: 'ACTIVE' },
            { label: 'Banned', value: 'BANNED' },
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
          showTotal: (t) => `Total ${t} users`,
        }}
        onChange={handleTableChange}
        onRow={(record) => ({
          onClick: () => navigate(`/users/${record.id}`),
          style: { cursor: 'pointer' },
        })}
        size="middle"
      />

      <Modal
        title="Grant Subscription Plan"
        open={grantUserId !== null}
        onCancel={() => {
          setGrantUserId(null);
          grantForm.resetFields();
        }}
        onOk={() => grantForm.submit()}
        okText="Grant"
        confirmLoading={grantMutation.isPending}
      >
        <Form<{ planCode: string }>
          form={grantForm}
          layout="vertical"
          onFinish={(values) => {
            if (grantUserId) {
              grantMutation.mutate({ userId: grantUserId, planCode: values.planCode });
            }
          }}
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
