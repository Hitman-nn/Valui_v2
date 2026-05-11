import { Table, Card, Row, Col, Typography, Tag, Space, TablePaginationConfig } from 'antd';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { usersApi } from '../../api/endpoints';
import StatCard from '../../components/StatCard';
import type { UserSummary } from '../../api/types';
import { useNavigate } from 'react-router-dom';

const { Text } = Typography;

export default function TokensPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);

  const { data: usersPage, isLoading } = useQuery({
    queryKey: ['token-users', page],
    queryFn: () => usersApi.list({ page, size: 20, sort: 'tokenBalance,desc' }),
  });

  const users = usersPage?._embedded?.users ?? [];
  const totalElements = usersPage?.page?.totalElements ?? 0;

  const totalBalance = users.reduce((sum, u) => sum + (u.tokenBalance ?? 0), 0);
  const zeroBalance  = users.filter(u => (u.tokenBalance ?? 0) === 0).length;
  const avgBalance   = users.length > 0 ? Math.round(totalBalance / users.length) : 0;

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
      render: (v: string | null) => v ? `@${v}` : <Text type="secondary">—</Text>,
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (v: string) => (
        <Tag color={v === 'ACTIVE' ? 'success' : v === 'BANNED' ? 'error' : 'default'}>{v}</Tag>
      ),
    },
    {
      title: 'Баланс токенов',
      dataIndex: 'tokenBalance',
      key: 'tokenBalance',
      width: 140,
      render: (v: number) => (
        <Space>
          <Text strong style={{ color: v > 0 ? '#52c41a' : '#ff4d4f' }}>{v}</Text>
          {v === 0 && <Tag color="error">Нет токенов</Tag>}
        </Space>
      ),
    },
    {
      title: 'Контроллеры',
      dataIndex: 'controllersCount',
      key: 'controllersCount',
      width: 120,
    },
    {
      title: 'Регистрация',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (v: string) => dayjs(v).format('DD.MM.YYYY'),
    },
  ];

  return (
    <>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} sm={8}>
          <StatCard
            title="Всего пользователей"
            value={totalElements}
            loading={isLoading}
          />
        </Col>
        <Col xs={24} sm={8}>
          <StatCard
            title="Ср. баланс (страница)"
            value={avgBalance}
            loading={isLoading}
            color="#1677ff"
          />
        </Col>
        <Col xs={24} sm={8}>
          <StatCard
            title="Без токенов (страница)"
            value={zeroBalance}
            loading={isLoading}
            color={zeroBalance > 0 ? '#ff4d4f' : undefined}
          />
        </Col>
      </Row>

      <Card title="Балансы токенов" size="small">
        <Table<UserSummary>
          dataSource={users}
          columns={columns}
          rowKey="id"
          loading={isLoading}
          pagination={{
            current: page + 1,
            pageSize: 20,
            total: totalElements,
            showSizeChanger: false,
            showTotal: (t) => `Всего ${t} пользователей`,
          }}
          onChange={handleTableChange}
          onRow={(u) => ({ onClick: () => navigate(`/users/${u.id}`), style: { cursor: 'pointer' } })}
          size="middle"
        />
      </Card>
    </>
  );
}
