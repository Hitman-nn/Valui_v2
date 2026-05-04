import { useState } from 'react';
import { Table, Alert, Tag, Space, Card, Row, Col, Typography, TablePaginationConfig } from 'antd';
import { WarningOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { subscriptionsApi } from '../../api/endpoints';
import StatCard from '../../components/StatCard';
import type { Subscription } from '../../api/types';

function planColor(planCode: string): string {
  switch (planCode.toUpperCase()) {
    case 'PREMIUM': return 'gold';
    case 'PRO': return 'blue';
    case 'FREE': return 'default';
    default: return 'purple';
  }
}

export default function SubscriptionsPage() {
  const [page, setPage] = useState(0);

  const { data: stats, isLoading: statsLoading } = useQuery({
    queryKey: ['subscription-stats'],
    queryFn: subscriptionsApi.stats,
  });

  const { data: expiring, isLoading: expiringLoading } = useQuery({
    queryKey: ['subscriptions-expiring'],
    queryFn: subscriptionsApi.expiring,
  });

  const { data: paged, isLoading: pagedLoading } = useQuery({
    queryKey: ['subscriptions', page],
    queryFn: () => subscriptionsApi.list({ page, size: 20 }),
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
      title: 'Plan',
      dataIndex: 'planCode',
      key: 'planCode',
      render: (code: string, record: Subscription) => (
        <Tag color={planColor(code)}>{record.planName}</Tag>
      ),
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      render: (v: string) => (
        <Tag color={v === 'ACTIVE' ? 'success' : 'default'}>{v}</Tag>
      ),
    },
    {
      title: 'Started',
      dataIndex: 'startedAt',
      key: 'startedAt',
      render: (v: string) => dayjs(v).format('DD.MM.YYYY HH:mm'),
    },
    {
      title: 'Expires',
      dataIndex: 'expiresAt',
      key: 'expiresAt',
      render: (v: string | null) => (v ? dayjs(v).format('DD.MM.YYYY HH:mm') : '—'),
    },
  ];

  const expiringColumns = [
    { title: 'Telegram ID', dataIndex: 'telegramId', key: 'telegramId' },
    { title: 'Username', dataIndex: 'username', key: 'username', render: (v: string | null) => v ?? '—' },
    {
      title: 'Plan',
      dataIndex: 'planCode',
      key: 'planCode',
      render: (code: string, record: Subscription) => (
        <Tag color={planColor(code)}>{record.planName}</Tag>
      ),
    },
    {
      title: 'Expires',
      dataIndex: 'expiresAt',
      key: 'expiresAt',
      render: (v: string | null) => (v ? dayjs(v).format('DD.MM.YYYY HH:mm') : '—'),
    },
  ];

  return (
    <>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} sm={8}>
          <StatCard
            title="Total Active"
            value={stats?.totalActive ?? 0}
            loading={statsLoading}
            color="#52c41a"
          />
        </Col>
        <Col xs={24} sm={8}>
          <StatCard
            title="Expiring in 24h"
            value={stats?.expiringIn24h ?? 0}
            loading={statsLoading}
            color={(stats?.expiringIn24h ?? 0) > 0 ? '#faad14' : undefined}
          />
        </Col>
        <Col xs={24} sm={8}>
          <StatCard
            title="Active Plans"
            value={stats?.byPlan?.length ?? 0}
            loading={statsLoading}
          />
        </Col>
      </Row>

      {/* Plan distribution */}
      {stats?.byPlan && stats.byPlan.length > 0 && (
        <Card title="Plan Distribution" style={{ marginBottom: 16 }} size="small">
          <Space wrap>
            {stats.byPlan.map((p) => (
              <Tag key={p.planCode} color={planColor(p.planCode)} style={{ fontSize: 14, padding: '4px 12px' }}>
                {p.planName}: <strong>{p.activeCount}</strong>
              </Tag>
            ))}
          </Space>
        </Card>
      )}

      {/* Expiring soon alert */}
      {(stats?.expiringIn24h ?? 0) > 0 && (
        <Alert
          type="warning"
          icon={<WarningOutlined />}
          showIcon
          message={`${stats!.expiringIn24h} subscription(s) expiring in the next 24 hours`}
          style={{ marginBottom: 16 }}
        />
      )}

      {/* Expiring subscriptions compact table */}
      {(expiring?.length ?? 0) > 0 && (
        <Card
          title="Expiring Soon"
          size="small"
          style={{ marginBottom: 16 }}
          extra={<Tag color="warning">{expiring?.length} subscriptions</Tag>}
        >
          <Table<Subscription>
            dataSource={expiring}
            columns={expiringColumns}
            rowKey="id"
            loading={expiringLoading}
            size="small"
            pagination={false}
          />
        </Card>
      )}

      {/* Main subscriptions table */}
      <Card title="All Active Subscriptions" size="small">
        <Table<Subscription>
          dataSource={paged?.content ?? []}
          columns={columns}
          rowKey="id"
          loading={pagedLoading}
          pagination={{
            current: page + 1,
            pageSize: 20,
            total: paged?.totalElements ?? 0,
            showSizeChanger: false,
            showTotal: (t) => `Total ${t} subscriptions`,
          }}
          onChange={handleTableChange}
          size="middle"
        />
      </Card>
    </>
  );
}
