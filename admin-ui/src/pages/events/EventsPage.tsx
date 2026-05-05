import { useState } from 'react';
import {
  Table,
  Card,
  Row,
  Col,
  Button,
  Tag,
  Space,
  Typography,
  Statistic,
  App,
  Popconfirm,
  TablePaginationConfig,
} from 'antd';
import { DeleteOutlined, ClearOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { eventsApi } from '../../api/endpoints';
import type { AdminEvent } from '../../api/types';

export default function EventsPage() {
  const [page, setPage] = useState(0);
  const { notification } = App.useApp();
  const qc = useQueryClient();

  const { data: events, isLoading } = useQuery({
    queryKey: ['admin-events', page],
    queryFn: () => eventsApi.list({ page, size: 25 }),
  });

  const { data: stats } = useQuery({
    queryKey: ['admin-events-stats'],
    queryFn: eventsApi.stats,
  });

  const deleteMutation = useMutation({
    mutationFn: eventsApi.delete,
    onSuccess: () => {
      notification.success({ message: 'Событие удалено' });
      qc.invalidateQueries({ queryKey: ['admin-events'] });
      qc.invalidateQueries({ queryKey: ['admin-events-stats'] });
    },
    onError: (e: Error) => notification.error({ message: e.message }),
  });

  const cleanupMutation = useMutation({
    mutationFn: eventsApi.deleteExpired,
    onSuccess: (count) => {
      notification.success({ message: `Удалено ${count} истёкших событий` });
      qc.invalidateQueries({ queryKey: ['admin-events'] });
      qc.invalidateQueries({ queryKey: ['admin-events-stats'] });
    },
    onError: (e: Error) => notification.error({ message: e.message }),
  });

  const handleTableChange = (p: TablePaginationConfig) => setPage((p.current ?? 1) - 1);

  const columns = [
    {
      title: 'Букмекер',
      dataIndex: 'bookmaker',
      key: 'bookmaker',
      width: 90,
      render: (v: string | null) => v ? <Tag>{v}</Tag> : <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: 'Контроллер',
      dataIndex: 'controllerTitle',
      key: 'controllerTitle',
      render: (v: string | null) => v ?? <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: 'Событие',
      dataIndex: 'title',
      key: 'title',
    },
    {
      title: 'External ID',
      dataIndex: 'eventExternalId',
      key: 'eventExternalId',
      width: 130,
      render: (v: string) => (
        <Typography.Text code style={{ fontSize: 11 }}>
          {v.length > 20 ? v.substring(0, 20) + '…' : v}
        </Typography.Text>
      ),
    },
    {
      title: 'Обнаружено',
      dataIndex: 'detectedAt',
      key: 'detectedAt',
      width: 150,
      render: (v: string) => dayjs(v).format('DD.MM.YY HH:mm'),
    },
    {
      title: 'Истекает',
      dataIndex: 'expiresAt',
      key: 'expiresAt',
      width: 150,
      render: (v: string | null) => {
        if (!v) return <Typography.Text type="secondary">—</Typography.Text>;
        const expired = dayjs(v).isBefore(dayjs());
        return (
          <Tag color={expired ? 'default' : 'processing'}>
            {dayjs(v).format('DD.MM.YY HH:mm')}
          </Tag>
        );
      },
    },
    {
      title: '',
      key: 'actions',
      width: 60,
      render: (_: unknown, e: AdminEvent) => (
        <Popconfirm
          title="Удалить событие?"
          onConfirm={() => deleteMutation.mutate(e.id)}
          okButtonProps={{ danger: true }}
          okText="Удалить"
        >
          <Button size="small" type="text" danger icon={<DeleteOutlined />} />
        </Popconfirm>
      ),
    },
  ];

  return (
    <>
      {/* Stats */}
      {stats && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col xs={12} sm={6}>
            <Card size="small">
              <Statistic title="Всего событий" value={stats.totalEvents} />
            </Card>
          </Col>
          <Col xs={12} sm={6}>
            <Card size="small">
              <Statistic title="Истёкших" value={stats.expiredEvents}
                valueStyle={{ color: stats.expiredEvents > 0 ? '#ff4d4f' : undefined }} />
            </Card>
          </Col>
          {stats.byBookmaker.slice(0, 4).map((b) => (
            <Col key={b.bookmaker} xs={12} sm={6}>
              <Card size="small">
                <Statistic title={b.bookmaker} value={b.count} />
              </Card>
            </Col>
          ))}
        </Row>
      )}

      <Space style={{ marginBottom: 16 }}>
        <Popconfirm
          title="Удалить все истёкшие события?"
          description="Это действие необратимо."
          onConfirm={() => cleanupMutation.mutate()}
          okButtonProps={{ danger: true }}
          okText="Удалить"
        >
          <Button
            icon={<ClearOutlined />}
            danger
            loading={cleanupMutation.isPending}
          >
            Очистить истёкшие
          </Button>
        </Popconfirm>
      </Space>

      <Table<AdminEvent>
        dataSource={events?.content ?? []}
        columns={columns}
        rowKey="id"
        loading={isLoading}
        pagination={{
          current: page + 1,
          pageSize: 25,
          total: events?.totalElements ?? 0,
          showSizeChanger: false,
          showTotal: (t) => `Всего ${t}`,
        }}
        onChange={handleTableChange}
        size="middle"
      />
    </>
  );
}
