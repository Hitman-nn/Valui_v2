import { useEffect, useState } from 'react';
import {
  Card, Col, Row, Statistic, Table, Tag, Tooltip, Typography, Badge, Space,
} from 'antd';
import {
  CheckCircleOutlined, CloseCircleOutlined, MinusCircleOutlined,
  ReloadOutlined, ClockCircleOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { jobsApi } from '../../api/endpoints';
import type { SystemTaskDto, PreMatchStats } from '../../api/types';

const { Title, Text } = Typography;

const REFRESH_MS = 30_000;

function fmtDuration(ms: number | null): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function fmtRelative(iso: string | null): string {
  if (!iso) return '—';
  const diffMs = Date.now() - new Date(iso).getTime();
  const secs = Math.floor(diffMs / 1000);
  if (secs < 60)   return `${secs}с назад`;
  if (secs < 3600) return `${Math.floor(secs / 60)}м назад`;
  if (secs < 86400) return `${Math.floor(secs / 3600)}ч назад`;
  return `${Math.floor(secs / 86400)}д назад`;
}

function fmtNext(iso: string | null): string {
  if (!iso) return '—';
  const diffMs = new Date(iso).getTime() - Date.now();
  if (diffMs < 0) return 'сейчас';
  const secs = Math.floor(diffMs / 1000);
  if (secs < 60)   return `через ${secs}с`;
  if (secs < 3600) return `через ${Math.floor(secs / 60)}м`;
  if (secs < 86400) return `через ${Math.floor(secs / 3600)}ч`;
  return `через ${Math.floor(secs / 86400)}д`;
}

function StatusTag({ status }: { status: SystemTaskDto['lastStatus'] }) {
  if (status === 'OK')
    return <Tag icon={<CheckCircleOutlined />} color="success">OK</Tag>;
  if (status === 'ERROR')
    return <Tag icon={<CloseCircleOutlined />} color="error">ERROR</Tag>;
  return <Tag icon={<MinusCircleOutlined />} color="default">—</Tag>;
}

function ModuleTag({ module }: { module: string }) {
  const colors: Record<string, string> = {
    monitor: 'blue', notify: 'purple', user: 'cyan',
    admin: 'geekblue', bot: 'orange',
  };
  return <Tag color={colors[module] ?? 'default'}>{module}</Tag>;
}

const COLUMNS: ColumnsType<SystemTaskDto> = [
  {
    title: 'Задача',
    dataIndex: 'displayName',
    render: (name: string, row) => (
      <Space direction="vertical" size={0}>
        <Text strong>{name}</Text>
        <Text type="secondary" style={{ fontSize: 11 }}>{row.key}</Text>
      </Space>
    ),
    width: 220,
  },
  {
    title: 'Модуль',
    dataIndex: 'module',
    render: (m: string) => <ModuleTag module={m} />,
    width: 90,
  },
  {
    title: 'Расписание',
    dataIndex: 'scheduleDescription',
    width: 160,
  },
  {
    title: 'Следующий запуск',
    dataIndex: 'nextFireTime',
    render: (iso: string | null) => (
      <Tooltip title={iso ? new Date(iso).toLocaleString() : '—'}>
        <Space>
          <ClockCircleOutlined style={{ color: '#1890ff' }} />
          {fmtNext(iso)}
        </Space>
      </Tooltip>
    ),
    width: 160,
    sorter: (a, b) => {
      const ta = a.nextFireTime ? new Date(a.nextFireTime).getTime() : Number.MAX_VALUE;
      const tb = b.nextFireTime ? new Date(b.nextFireTime).getTime() : Number.MAX_VALUE;
      return ta - tb;
    },
    defaultSortOrder: 'ascend',
  },
  {
    title: 'Последний запуск',
    dataIndex: 'lastRunAt',
    render: (iso: string | null) => (
      <Tooltip title={iso ? new Date(iso).toLocaleString() : 'Ещё не запускался'}>
        {fmtRelative(iso)}
      </Tooltip>
    ),
    width: 150,
  },
  {
    title: 'Длит.',
    dataIndex: 'lastDurationMs',
    render: fmtDuration,
    width: 90,
    align: 'right',
  },
  {
    title: 'Статус',
    dataIndex: 'lastStatus',
    render: (s: SystemTaskDto['lastStatus']) => <StatusTag status={s} />,
    width: 90,
    filters: [
      { text: 'OK',       value: 'OK' },
      { text: 'ERROR',    value: 'ERROR' },
      { text: 'Не запускался', value: 'NEVER_RUN' },
    ],
    onFilter: (value, record) => record.lastStatus === value,
  },
];

function PreMatchCard({ stats }: { stats: PreMatchStats }) {
  const retryEntries = Object.entries(stats.retryAttempts);
  return (
    <Card
      title="Pre-match снапшоты"
      style={{ height: '100%' }}
      extra={
        <Tooltip title="Ставки, для которых запланировано снятие коэффициентов за 30 мин до матча">
          <Text type="secondary" style={{ fontSize: 12 }}>PreMatchOddsService</Text>
        </Tooltip>
      }
    >
      <Row gutter={[24, 24]}>
        <Col span={12}>
          <Statistic
            title="Ожидают снапшота"
            value={stats.pendingSnapshots}
            prefix={<ClockCircleOutlined />}
            valueStyle={{ color: stats.pendingSnapshots > 0 ? '#1890ff' : '#52c41a' }}
          />
        </Col>
        <Col span={12}>
          <Statistic
            title="Активные ретраи"
            value={stats.activeRetries}
            valueStyle={{ color: stats.activeRetries > 0 ? '#faad14' : '#52c41a' }}
          />
        </Col>
        <Col span={12}>
          <Statistic
            title="HTTP слоты (своб. / 4)"
            value={stats.fetchSemaphoreAvailable}
            suffix="/ 4"
            valueStyle={{ color: stats.fetchSemaphoreAvailable === 0 ? '#f5222d' : '#52c41a' }}
          />
        </Col>
        <Col span={12}>
          <div>
            <Text type="secondary">Счётчики ретраев</Text>
            {retryEntries.length === 0 ? (
              <div><Text type="secondary">—</Text></div>
            ) : (
              <div style={{ marginTop: 4 }}>
                {retryEntries.map(([slipId, count]) => (
                  <div key={slipId} style={{ fontSize: 12 }}>
                    <Text code style={{ fontSize: 10 }}>{slipId.substring(0, 8)}…</Text>
                    {' '}
                    <Badge count={count} style={{ backgroundColor: '#faad14' }} />
                  </div>
                ))}
              </div>
            )}
          </div>
        </Col>
      </Row>
    </Card>
  );
}

export default function JobsPage() {
  const [data, setData]       = useState<{ tasks: SystemTaskDto[]; preMatch: PreMatchStats } | null>(null);
  const [loading, setLoading] = useState(true);
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null);

  const load = () => {
    setLoading(true);
    jobsApi.overview()
      .then((d) => { setData(d); setLastRefresh(new Date()); })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    const id = setInterval(load, REFRESH_MS);
    return () => clearInterval(id);
  }, []);

  const errorCount = data?.tasks.filter((t) => t.lastStatus === 'ERROR').length ?? 0;

  return (
    <div style={{ padding: 24 }}>
      <Row justify="space-between" align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Title level={4} style={{ margin: 0 }}>
            Системные задачи
          </Title>
          <Text type="secondary" style={{ fontSize: 12 }}>
            Автообновление каждые 30 с
            {lastRefresh && ` · обновлено ${lastRefresh.toLocaleTimeString()}`}
          </Text>
        </Col>
        <Col>
          <ReloadOutlined
            onClick={load}
            spin={loading}
            style={{ cursor: 'pointer', fontSize: 16 }}
          />
        </Col>
      </Row>

      {errorCount > 0 && (
        <Card
          style={{ marginBottom: 16, borderColor: '#f5222d', backgroundColor: '#fff2f0' }}
          size="small"
        >
          <Text type="danger">
            <CloseCircleOutlined /> {errorCount} задача(и) завершились с ошибкой
          </Text>
        </Card>
      )}

      <Row gutter={[16, 16]}>
        <Col span={24} xl={16}>
          <Card title="Запланированные задачи" loading={loading}>
            <Table<SystemTaskDto>
              dataSource={data?.tasks ?? []}
              columns={COLUMNS}
              rowKey="key"
              pagination={false}
              size="small"
              rowClassName={(row) =>
                row.lastStatus === 'ERROR' ? 'ant-table-row-error' : ''
              }
            />
          </Card>
        </Col>
        <Col span={24} xl={8}>
          {data?.preMatch && <PreMatchCard stats={data.preMatch} />}
        </Col>
      </Row>

      <style>{`
        .ant-table-row-error td { background: #fff2f0 !important; }
      `}</style>
    </div>
  );
}
