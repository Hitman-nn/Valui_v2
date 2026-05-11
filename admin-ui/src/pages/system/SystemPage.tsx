import { Card, Table, Tag, Row, Col, Progress, Space, Typography } from 'antd';
import {
  UserOutlined,
  ThunderboltOutlined,
  ApiOutlined,
  BellOutlined,
  CalendarOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { systemApi } from '../../api/endpoints';
import StatCard from '../../components/StatCard';
import type { KafkaLag } from '../../api/types';

export default function SystemPage() {
  const { data: overview, isLoading: ovLoading } = useQuery({
    queryKey: ['system-overview'],
    queryFn: systemApi.overview,
    refetchInterval: 30_000,
  });

  const { data: kafka, isLoading: kafkaLoading } = useQuery({
    queryKey: ['system-kafka'],
    queryFn: systemApi.kafka,
    refetchInterval: 30_000,
  });

  const { data: redis, isLoading: redisLoading } = useQuery({
    queryKey: ['system-redis'],
    queryFn: systemApi.redis,
    refetchInterval: 30_000,
  });

  const { data: db, isLoading: dbLoading } = useQuery({
    queryKey: ['system-db'],
    queryFn: systemApi.db,
    refetchInterval: 30_000,
  });

  const kafkaColumns = [
    { title: 'Group ID', dataIndex: 'groupId', key: 'groupId' },
    {
      title: 'Lag by Topic',
      dataIndex: 'lagByTopic',
      key: 'lagByTopic',
      render: (lagByTopic: Record<string, number>) => (
        <Space wrap>
          {Object.entries(lagByTopic).map(([topic, lag]) => (
            <Tag key={topic} color={lag > 100 ? 'error' : lag > 10 ? 'warning' : 'default'}>
              {topic}: {lag}
            </Tag>
          ))}
        </Space>
      ),
    },
    {
      title: 'Total Lag',
      dataIndex: 'totalLag',
      key: 'totalLag',
      width: 120,
      render: (lag: number) => (
        <Typography.Text strong style={{ color: lag > 100 ? '#ff4d4f' : undefined }}>
          {lag}
        </Typography.Text>
      ),
    },
  ];

  const dbUtilization = db
    ? Math.round((db.activeConnections / db.maxPoolSize) * 100)
    : 0;

  const redisUtilization = redis
    ? redis.maxMemoryBytes > 0
      ? Math.round((redis.usedMemoryBytes / redis.maxMemoryBytes) * 100)
      : Math.round((redis.usedMemoryBytes / redis.usedMemoryPeakBytes) * 100)
    : 0;
  const redisLabel = redis && redis.maxMemoryBytes > 0 ? 'Memory Usage (of limit)' : 'Memory Usage (vs peak)';

  return (
    <>
      {/* Overview */}
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} sm={12} md={8} lg={6} xl={4} style={{ flex: 1 }}>
          <StatCard
            title="Total Users"
            value={overview?.totalUsers ?? 0}
            prefix={<UserOutlined />}
            loading={ovLoading}
          />
        </Col>
        <Col xs={24} sm={12} md={8} lg={6} xl={4} style={{ flex: 1 }}>
          <StatCard
            title="Active Users"
            value={overview?.activeUsers ?? 0}
            prefix={<UserOutlined />}
            loading={ovLoading}
            color="#52c41a"
          />
        </Col>
        <Col xs={24} sm={12} md={8} lg={6} xl={4} style={{ flex: 1 }}>
          <StatCard
            title="Active Controllers"
            value={overview?.activeControllers ?? 0}
            prefix={<ApiOutlined />}
            loading={ovLoading}
            color="#1668dc"
          />
        </Col>
        <Col xs={24} sm={12} md={8} lg={6} xl={4} style={{ flex: 1 }}>
          <StatCard
            title="Events Today"
            value={overview?.eventsToday ?? 0}
            prefix={<CalendarOutlined />}
            loading={ovLoading}
          />
        </Col>
        <Col xs={24} sm={12} md={8} lg={6} xl={4} style={{ flex: 1 }}>
          <StatCard
            title="Notifications Today"
            value={overview?.notificationsToday ?? 0}
            prefix={<BellOutlined />}
            loading={ovLoading}
            color="#722ed1"
          />
        </Col>
      </Row>

      {/* Kafka */}
      <Card
        title={
          <Space>
            <ThunderboltOutlined />
            <span>Kafka Consumer Lag</span>
          </Space>
        }
        style={{ marginBottom: 16 }}
        size="small"
      >
        <Table<KafkaLag>
          dataSource={kafka ?? []}
          columns={kafkaColumns}
          rowKey="groupId"
          loading={kafkaLoading}
          pagination={false}
          size="small"
        />
      </Card>

      <Row gutter={16}>
        {/* Redis */}
        <Col xs={24} md={12}>
          <Card
            title="Redis"
            loading={redisLoading}
            size="small"
            style={{ marginBottom: 16 }}
          >
            {redis && (
              <Space direction="vertical" style={{ width: '100%' }} size="small">
                <div>
                  <Typography.Text type="secondary">{redisLabel}</Typography.Text>
                  <Progress
                    percent={redisUtilization}
                    format={() => redis.usedMemoryHuman}
                    strokeColor={redisUtilization > 80 ? '#ff4d4f' : '#1668dc'}
                  />
                </div>
                <Row gutter={16}>
                  <Col span={12}>
                    <Card size="small">
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>Used Memory</Typography.Text>
                      <br />
                      <Typography.Text strong>{redis.usedMemoryHuman}</Typography.Text>
                    </Card>
                  </Col>
                  <Col span={12}>
                    <Card size="small">
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>Total Keys</Typography.Text>
                      <br />
                      <Typography.Text strong>{redis.totalKeys.toLocaleString()}</Typography.Text>
                    </Card>
                  </Col>
                </Row>
              </Space>
            )}
          </Card>
        </Col>

        {/* DB Pool */}
        <Col xs={24} md={12}>
          <Card
            title="Database Connection Pool"
            loading={dbLoading}
            size="small"
            style={{ marginBottom: 16 }}
          >
            {db && (
              <Space direction="vertical" style={{ width: '100%' }} size="small">
                <div>
                  <Typography.Text type="secondary">Pool Utilization</Typography.Text>
                  <Progress
                    percent={dbUtilization}
                    format={() => `${db.activeConnections}/${db.maxPoolSize}`}
                    strokeColor={dbUtilization > 80 ? '#ff4d4f' : dbUtilization > 60 ? '#faad14' : '#52c41a'}
                  />
                </div>
                <Row gutter={[8, 8]}>
                  {[
                    { label: 'Active', value: db.activeConnections, color: '#1668dc' },
                    { label: 'Idle', value: db.idleConnections, color: '#52c41a' },
                    { label: 'Pending', value: db.pendingConnections, color: '#faad14' },
                    { label: 'Total', value: db.totalConnections, color: undefined },
                    { label: 'Max Pool', value: db.maxPoolSize, color: undefined },
                  ].map(({ label, value, color }) => (
                    <Col key={label} span={8}>
                      <Card size="small" styles={{ body: { padding: '8px 12px' } }}>
                        <Typography.Text type="secondary" style={{ fontSize: 11 }}>{label}</Typography.Text>
                        <br />
                        <Typography.Text strong style={color ? { color } : undefined}>
                          {value}
                        </Typography.Text>
                      </Card>
                    </Col>
                  ))}
                </Row>
              </Space>
            )}
          </Card>
        </Col>
      </Row>
    </>
  );
}
