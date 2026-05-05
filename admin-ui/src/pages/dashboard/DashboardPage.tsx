import {
  Row,
  Col,
  Card,
  Statistic,
  Tag,
  Progress,
  Table,
  Space,
  Typography,
  Tooltip,
  Spin,
  Alert,
  Badge,
} from 'antd';
import {
  UserOutlined,
  ApiOutlined,
  BellOutlined,
  CalendarOutlined,
  ThunderboltOutlined,
  DatabaseOutlined,
  CloudServerOutlined,
  MonitorOutlined,
  ClockCircleOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { dashboardApi } from '../../api/endpoints';
import type { DashboardFull, ActivityPoint, KafkaLag } from '../../api/types';

const { Text } = Typography;

function fmtBytes(bytes: number): string {
  if (bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(1)} ${units[i]}`;
}

function fmtUptime(sec: number): string {
  const d = Math.floor(sec / 86400);
  const h = Math.floor((sec % 86400) / 3600);
  const m = Math.floor((sec % 3600) / 60);
  return d > 0 ? `${d}d ${h}h` : `${h}h ${m}m`;
}

function planColor(code: string): string {
  switch (code.toUpperCase()) {
    case 'PREMIUM': return 'gold';
    case 'PRO':     return 'blue';
    default:        return 'default';
  }
}

export default function DashboardPage() {
  const navigate = useNavigate();

  const { data, isLoading, error } = useQuery({
    queryKey: ['dashboard-full'],
    queryFn: () => dashboardApi.full(14),
    refetchInterval: 30_000,
  });

  if (isLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (error || !data) {
    return (
      <Alert type="error" message="Не удалось загрузить данные дашборда" showIcon />
    );
  }

  const { summary, parsers, redis, kafka, db, jvm, activity } = data as DashboardFull;

  const heapPct = jvm.heapMaxBytes > 0
    ? Math.round((jvm.heapUsedBytes / jvm.heapMaxBytes) * 100) : 0;
  const dbPct = db.maxPoolSize > 0
    ? Math.round((db.activeConnections / db.maxPoolSize) * 100) : 0;
  const diskPct = jvm.diskTotalBytes > 0
    ? Math.round(((jvm.diskTotalBytes - jvm.diskFreeBytes) / jvm.diskTotalBytes) * 100) : 0;

  const activityColumns = [
    { title: 'Дата', dataIndex: 'date', key: 'date', width: 120 },
    {
      title: 'События',
      dataIndex: 'events',
      key: 'events',
      render: (v: number) => <Text strong style={{ color: '#1668dc' }}>{v.toLocaleString()}</Text>,
    },
    {
      title: 'Уведомления',
      dataIndex: 'notifications',
      key: 'notifications',
      render: (v: number) => <Text strong style={{ color: '#722ed1' }}>{v.toLocaleString()}</Text>,
    },
  ];

  const kafkaColumns = [
    { title: 'Group', dataIndex: 'groupId', key: 'groupId', width: 160 },
    {
      title: 'Lag',
      dataIndex: 'totalLag',
      key: 'totalLag',
      render: (lag: number) => (
        <Tag color={lag > 100 ? 'error' : lag > 0 ? 'warning' : 'success'}>
          {lag < 0 ? 'N/A' : lag}
        </Tag>
      ),
    },
  ];

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={16}>

      {/* ── 1а. Users ───────────────────────────────────────────────────── */}
      <Card title={<Space><UserOutlined /><span>Пользователи</span></Space>} size="small">
        <Row gutter={[16, 8]}>
          <Col xs={12} sm={8} md={4}>
            <Tooltip title="Все зарегистрированные пользователи">
              <div onClick={() => navigate('/users')} style={{ cursor: 'pointer' }}>
                <Statistic title="Всего" value={summary.users.total} />
              </div>
            </Tooltip>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Tooltip title="Пользователи со статусом ACTIVE">
              <div onClick={() => navigate('/users?status=ACTIVE')} style={{ cursor: 'pointer' }}>
                <Statistic title="Активных" value={summary.users.active}
                  valueStyle={{ color: '#52c41a' }} />
              </div>
            </Tooltip>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Tooltip title="Пользователи со статусом BANNED">
              <div onClick={() => navigate('/users?status=BANNED')} style={{ cursor: 'pointer' }}>
                <Statistic title="Забаненных" value={summary.users.banned}
                  valueStyle={{ color: summary.users.banned > 0 ? '#ff4d4f' : undefined }} />
              </div>
            </Tooltip>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Tooltip title="Зарегистрировались сегодня">
              <Statistic title="Новых сегодня" value={summary.users.newToday}
                valueStyle={{ color: '#1668dc' }} />
            </Tooltip>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Tooltip title="Зарегистрировались за последние 7 дней">
              <Statistic title="За неделю" value={summary.users.newThisWeek} />
            </Tooltip>
          </Col>
        </Row>
      </Card>

      {/* ── 1а. Controllers ─────────────────────────────────────────────── */}
      <Card title={<Space><ApiOutlined /><span>Контроллеры</span></Space>} size="small">
        <Row gutter={[16, 8]}>
          <Col xs={12} sm={8} md={4}>
            <div onClick={() => navigate('/controllers')} style={{ cursor: 'pointer' }}>
              <Statistic title="Всего" value={summary.controllers.total} />
            </div>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Statistic title="Активных" value={summary.controllers.active}
              valueStyle={{ color: '#52c41a' }} />
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Statistic title="Muted" value={summary.controllers.muted}
              valueStyle={{ color: summary.controllers.muted > 0 ? '#faad14' : undefined }} />
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Tooltip title="Активные контроллеры без события более 7 дней">
              <Statistic title="Неактивных долго" value={summary.controllers.stale}
                valueStyle={{ color: summary.controllers.stale > 0 ? '#ff4d4f' : undefined }} />
            </Tooltip>
          </Col>
        </Row>
      </Card>

      {/* ── 1а. Events + Notifications ──────────────────────────────────── */}
      <Row gutter={16}>
        <Col xs={24} md={12}>
          <Card title={<Space><CalendarOutlined /><span>События</span></Space>} size="small">
            <Row gutter={[8, 8]}>
              {[
                { label: 'Сегодня', value: summary.events.today },
                { label: 'Неделя',  value: summary.events.thisWeek },
                { label: 'Месяц',   value: summary.events.thisMonth },
                { label: 'Год',     value: summary.events.thisYear },
                { label: 'Всего',   value: summary.events.total },
              ].map(({ label, value }) => (
                <Col key={label} span={8}>
                  <Statistic title={label} value={value} />
                </Col>
              ))}
            </Row>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card title={<Space><BellOutlined /><span>Уведомления</span></Space>} size="small">
            <Row gutter={[8, 8]}>
              {[
                { label: 'Сегодня', value: summary.notifications.today },
                { label: 'Неделя',  value: summary.notifications.thisWeek },
                { label: 'Месяц',   value: summary.notifications.thisMonth },
                { label: 'Год',     value: summary.notifications.thisYear },
                { label: 'Всего',   value: summary.notifications.total },
              ].map(({ label, value }) => (
                <Col key={label} span={8}>
                  <Statistic title={label} value={value} valueStyle={{ color: '#722ed1' }} />
                </Col>
              ))}
            </Row>
          </Card>
        </Col>
      </Row>

      {/* ── 1а. Subscriptions ───────────────────────────────────────────── */}
      <Card title={<Space><ClockCircleOutlined /><span>Подписки</span></Space>} size="small">
        <Row gutter={[16, 8]} align="middle">
          <Col>
            <Space wrap>
              {summary.subscriptions.byPlan.map((p) => (
                <Tag key={p.planCode} color={planColor(p.planCode)}
                  style={{ fontSize: 14, padding: '4px 12px' }}>
                  {p.planName}: <strong>{p.count}</strong>
                </Tag>
              ))}
            </Space>
          </Col>
          <Col>
            <Statistic
              title={<Tooltip title="Активные подписки с истечением ≤ 7 дней">Истекают через 7д</Tooltip>}
              value={summary.subscriptions.expiringIn7d}
              valueStyle={{ color: summary.subscriptions.expiringIn7d > 0 ? '#faad14' : undefined }}
            />
          </Col>
        </Row>
      </Card>

      {/* ── 1б. Parsers ─────────────────────────────────────────────────── */}
      <Card title={<Space><MonitorOutlined /><span>Парсеры</span></Space>} size="small">
        <Row gutter={[8, 8]}>
          {parsers.map((p) => (
            <Col key={p.bookmaker} xs={12} sm={8} md={4}>
              <Card
                size="small"
                style={{ borderLeft: `4px solid ${p.indicator === 'green' ? '#52c41a' : p.indicator === 'red' ? '#ff4d4f' : '#faad14'}` }}
              >
                <Text strong>{p.bookmaker}</Text>
                <br />
                <Badge
                  status={p.indicator === 'green' ? 'success' : p.indicator === 'red' ? 'error' : 'warning'}
                  text={<Text style={{ fontSize: 12 }}>{p.cbState}</Text>}
                />
                <br />
                <Progress percent={Math.round(p.successRate)} size="small"
                  strokeColor={p.indicator === 'green' ? '#52c41a' : p.indicator === 'red' ? '#ff4d4f' : '#faad14'}
                  format={(pct) => `${pct}%`} />
              </Card>
            </Col>
          ))}
        </Row>
      </Card>

      {/* ── Infrastructure ──────────────────────────────────────────────── */}
      <Row gutter={16}>
        {/* Redis */}
        <Col xs={24} md={8}>
          <Card title={<Space><DatabaseOutlined /><span>Redis</span></Space>} size="small">
            <Statistic title="Используется" value={redis.usedMemoryHuman} />
            <Progress percent={jvm.heapUsedBytes > 0 ? Math.min(99, Math.round((redis.usedMemoryBytes / redis.usedMemoryPeakBytes) * 100)) : 0}
              strokeColor={redis.usedMemoryBytes / redis.usedMemoryPeakBytes > 0.8 ? '#ff4d4f' : '#1668dc'}
              style={{ marginTop: 8 }} />
            <Text type="secondary" style={{ fontSize: 12 }}>Ключей: {redis.totalKeys.toLocaleString()}</Text>
          </Card>
        </Col>

        {/* DB Pool */}
        <Col xs={24} md={8}>
          <Card title={<Space><DatabaseOutlined /><span>HikariCP</span></Space>} size="small">
            <Progress percent={dbPct}
              format={() => `${db.activeConnections}/${db.maxPoolSize}`}
              strokeColor={dbPct > 80 ? '#ff4d4f' : dbPct > 60 ? '#faad14' : '#52c41a'} />
            <Row gutter={8} style={{ marginTop: 8 }}>
              <Col span={8}><Statistic title="Active" value={db.activeConnections} /></Col>
              <Col span={8}><Statistic title="Idle" value={db.idleConnections} /></Col>
              <Col span={8}><Statistic title="Pending" value={db.pendingConnections} /></Col>
            </Row>
          </Card>
        </Col>

        {/* Kafka */}
        <Col xs={24} md={8}>
          <Card title={<Space><ThunderboltOutlined /><span>Kafka Lag</span></Space>} size="small">
            <Table<KafkaLag>
              dataSource={kafka}
              columns={kafkaColumns}
              rowKey="groupId"
              pagination={false}
              size="small"
            />
          </Card>
        </Col>
      </Row>

      {/* ── JVM ─────────────────────────────────────────────────────────── */}
      <Card title={<Space><CloudServerOutlined /><span>JVM / Система</span></Space>} size="small">
        <Row gutter={[16, 8]}>
          <Col xs={12} sm={8} md={4}>
            <Tooltip title="Heap: используемая / максимальная память JVM">
              <Statistic title="Heap" value={`${fmtBytes(jvm.heapUsedBytes)} / ${fmtBytes(jvm.heapMaxBytes)}`} />
              <Progress percent={heapPct} size="small"
                strokeColor={heapPct > 80 ? '#ff4d4f' : '#52c41a'} />
            </Tooltip>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Statistic title="Non-Heap" value={fmtBytes(jvm.nonHeapUsedBytes)} />
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Statistic title="Потоки" value={jvm.threadsLive} />
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Statistic title="CPU" value={`${jvm.cpuUsagePct.toFixed(1)}%`}
              valueStyle={{ color: jvm.cpuUsagePct > 80 ? '#ff4d4f' : undefined }} />
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Tooltip title="Диск: занято / всего">
              <Statistic title="Диск" value={`${fmtBytes(jvm.diskTotalBytes - jvm.diskFreeBytes)} / ${fmtBytes(jvm.diskTotalBytes)}`} />
              <Progress percent={diskPct} size="small"
                strokeColor={diskPct > 85 ? '#ff4d4f' : '#52c41a'} />
            </Tooltip>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Statistic title="Uptime" value={fmtUptime(jvm.uptimeSeconds)} />
          </Col>
        </Row>
      </Card>

      {/* ── Activity Chart ──────────────────────────────────────────────── */}
      <Card title="Активность за 14 дней" size="small">
        <Table<ActivityPoint>
          dataSource={[...activity].reverse()}
          columns={activityColumns}
          rowKey="date"
          pagination={false}
          size="small"
          scroll={{ x: 400 }}
        />
      </Card>

    </Space>
  );
}
