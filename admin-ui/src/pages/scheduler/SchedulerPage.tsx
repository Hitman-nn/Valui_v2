import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  Col,
  Form,
  InputNumber,
  message,
  Modal,
  Row,
  Segmented,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
  LoadingOutlined,
  ReloadOutlined,
  SettingOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip as RTooltip,
  Legend, ResponsiveContainer,
} from 'recharts';
import { schedulerApi } from '../../api/endpoints';
import type {
  ControllerJobDto,
  SchedulerConfig,
  ControllerJobDetail,
  SchedulerMetricsSnapshot,
  PollHistoryHourlyDto,
} from '../../api/types';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import 'dayjs/locale/ru';

dayjs.extend(relativeTime);
dayjs.locale('ru');

const { Text, Title } = Typography;

// ── Descriptions for each stat card ──────────────────────────────────────────

const STAT_TIPS: Record<string, string> = {
  'Заданий в реестре':
    'Общее количество контроллеров, зарегистрированных в планировщике. Совпадает с числом активных контроллеров в БД.',
  'В очереди (DelayQueue)':
    'Сколько заданий ожидают своего времени в приоритетной очереди. Высокое значение означает, что задания накапливаются быстрее, чем успевают запускаться.',
  'Свободных слотов':
    'Свободные места в глобальном пуле параллельного выполнения. Показатель "0" — пул насыщен; новые задания откладываются (defer), но не теряются.',
  'Задержка диспетчера p95':
    '95-й перцентиль задержки между запланированным временем запуска и фактическим. Нормально: < 1с. При > 5с ищите нагрузку или зависшие задачи.',
  'Голодание (макс.)':
    'Сколько секунд прошло с последнего запуска наиболее "давно не запускавшегося" контроллера. В норме ≤ 2× poll_interval.',
  'Дефер (всего)':
    'Суммарное число раз, когда задание было отложено из-за насыщения пула. В отличие от "пропуска" — задание всё равно выполнится.',
  'Событий обнаружено':
    'Кумулятивное число новых спортивных событий, найденных с момента старта сервиса.',
  'Задача p95':
    '95-й перцентиль полного времени выполнения одного опроса (HTTP + дедупликация + запись). Нормально: < 3с. Превышение fetch-budget (8с по умолчанию) фиксируется как timeout.',
};

// ── helpers ───────────────────────────────────────────────────────────────────

function fmtMs(ms: number): string {
  if (ms <= 0 || !isFinite(ms)) return '—';
  if (ms < 1000) return `${Math.round(ms)} мс`;
  return `${(ms / 1000).toFixed(1)} с`;
}

function fmtRelative(iso: string | null): string {
  if (!iso) return '—';
  return dayjs(iso).fromNow();
}

function fmtNextRun(job: ControllerJobDto): string {
  if (job.inFlight) return 'выполняется…';
  const diff = dayjs(job.nextRunAt).diff(dayjs(), 'second');
  if (diff <= 0) return `${Math.abs(diff)} с назад`;
  if (diff < 60)  return `через ${diff} с`;
  return `через ${Math.round(diff / 60)} мин`;
}

function bookmakerLabel(bm: string | null): string {
  if (!bm) return '—';
  const MAP: Record<string, string> = {
    XBET: '1xBet', FONBET: 'Fonbet', OLIMP: 'Olimp',
    BETCITY: 'BetCity', BETBOOM: 'BetBoom',
  };
  return MAP[bm] ?? bm;
}

function JobStatusTag({ job }: { job: ControllerJobDto }) {
  if (job.inFlight)
    return <Tag icon={<LoadingOutlined />} color="processing">Выполняется</Tag>;
  if (job.status === 'LATE')
    return (
      <Tag icon={<ExclamationCircleOutlined />} color="warning">
        Просрочено {job.overdueSec} с
      </Tag>
    );
  return <Tag icon={<CheckCircleOutlined />} color="default">Ожидает</Tag>;
}

// ── Metrics chart ─────────────────────────────────────────────────────────────

function MetricsChart() {
  const [range, setRange] = useState<'1h' | '6h' | '24h'>('1h');

  const { data = [], isLoading } = useQuery<SchedulerMetricsSnapshot[]>({
    queryKey: ['scheduler-metrics-history', range],
    queryFn: () => schedulerApi.metricsHistory(range),
    refetchInterval: 30_000,
  });

  const chartData = data.map((s) => ({
    time: dayjs(s.ts).format(range === '24h' ? 'DD.MM HH:mm' : 'HH:mm'),
    'Очередь':          s.queueDepth,
    'inFlight':         s.inFlight,
    'Lag p95 (мс)':     Math.round(s.lagP95Ms),
    'Задача p95 (мс)':  Math.round(s.taskDurP95Ms),
  }));

  return (
    <Card
      title={
        <Space>
          <ThunderboltOutlined />
          История метрик
          <Tooltip title="Обновляется каждые 30 секунд. Данные хранятся 24 часа в Redis.">
            <InfoCircleOutlined style={{ color: '#999', fontSize: 12 }} />
          </Tooltip>
        </Space>
      }
      extra={
        <Segmented
          size="small"
          options={['1h', '6h', '24h']}
          value={range}
          onChange={(v) => setRange(v as '1h' | '6h' | '24h')}
        />
      }
    >
      {isLoading || chartData.length === 0 ? (
        <Text type="secondary">
          {isLoading ? 'Загрузка…' : 'Данных пока нет — страница только открылась. График появится через 30 с.'}
        </Text>
      ) : (
        <ResponsiveContainer width="100%" height={220}>
          <LineChart data={chartData} margin={{ top: 4, right: 16, bottom: 0, left: 0 }}>
            <CartesianGrid strokeDasharray="3 3" opacity={0.3} />
            <XAxis dataKey="time" tick={{ fontSize: 11 }} />
            <YAxis yAxisId="count" tick={{ fontSize: 11 }} width={35} />
            <YAxis yAxisId="ms" orientation="right" tick={{ fontSize: 11 }} width={50} unit=" мс" />
            <RTooltip />
            <Legend iconSize={10} wrapperStyle={{ fontSize: 12 }} />
            <Line yAxisId="count" dataKey="Очередь"        stroke="#1677ff" dot={false} strokeWidth={2} />
            <Line yAxisId="count" dataKey="inFlight"        stroke="#52c41a" dot={false} strokeWidth={2} />
            <Line yAxisId="ms"    dataKey="Lag p95 (мс)"   stroke="#fa8c16" dot={false} strokeWidth={1.5} strokeDasharray="4 2" />
            <Line yAxisId="ms"    dataKey="Задача p95 (мс)" stroke="#722ed1" dot={false} strokeWidth={1.5} strokeDasharray="4 2" />
          </LineChart>
        </ResponsiveContainer>
      )}
    </Card>
  );
}

// ── Hourly stats chart (per controller) ──────────────────────────────────────

function HourlyStatsChart({ controllerId }: { controllerId: string }) {
  const { data = [], isLoading } = useQuery<PollHistoryHourlyDto[]>({
    queryKey: ['scheduler-hourly-stats', controllerId],
    queryFn: () => schedulerApi.jobHourlyStats(controllerId, 24),
  });

  const chartData = data.map((d) => ({
    hour: dayjs(d.hour).format('DD.MM HH:mm'),
    Опросов:     d.totalPolls,
    Ошибок:      d.errorCount,
    'Событий':   d.totalEvents,
    'Ср. длит. (мс)': d.avgDurationMs,
  }));

  if (isLoading) return <Text type="secondary">Загрузка аналитики…</Text>;
  if (!chartData.length) return <Text type="secondary">Нет данных за 24 часа. История наполняется по мере работы.</Text>;

  return (
    <ResponsiveContainer width="100%" height={180}>
      <LineChart data={chartData} margin={{ top: 4, right: 16, bottom: 0, left: 0 }}>
        <CartesianGrid strokeDasharray="3 3" opacity={0.3} />
        <XAxis dataKey="hour" tick={{ fontSize: 10 }} />
        <YAxis yAxisId="count" tick={{ fontSize: 10 }} width={30} />
        <YAxis yAxisId="ms" orientation="right" tick={{ fontSize: 10 }} width={50} unit=" мс" />
        <RTooltip />
        <Legend iconSize={10} wrapperStyle={{ fontSize: 11 }} />
        <Line yAxisId="count" dataKey="Опросов"  stroke="#1677ff" dot={false} strokeWidth={2} />
        <Line yAxisId="count" dataKey="Ошибок"   stroke="#ff4d4f" dot={false} strokeWidth={2} />
        <Line yAxisId="ms"    dataKey="Ср. длит. (мс)" stroke="#fa8c16" dot={false} strokeWidth={1.5} />
      </LineChart>
    </ResponsiveContainer>
  );
}

// ── Job detail modal ──────────────────────────────────────────────────────────

function JobDetailModal({
  job,
  open,
  onClose,
}: {
  job: ControllerJobDto;
  open: boolean;
  onClose: () => void;
}) {
  const { data, isLoading } = useQuery<ControllerJobDetail>({
    queryKey: ['scheduler-job', job.controllerId],
    queryFn: () => schedulerApi.jobDetail(job.controllerId),
    enabled: open,
    refetchInterval: 5000,
  });

  const histCols = [
    {
      title: 'Начало',
      dataIndex: 'startedAt',
      render: (v: string) => (
        <Tooltip title={dayjs(v).format('DD.MM HH:mm:ss')}>
          {dayjs(v).fromNow()}
        </Tooltip>
      ),
    },
    {
      title: 'Длительность',
      dataIndex: 'durationMs',
      render: (v: number) => fmtMs(v),
    },
    {
      title: 'Событий',
      dataIndex: 'eventsFound',
      render: (v: number) =>
        v < 0 ? <Text type="danger">ошибка</Text> : <Text>{v}</Text>,
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      render: (v: string) =>
        v === 'ok'      ? <Tag color="success">ok</Tag>
        : v === 'timeout' ? <Tag color="warning">timeout</Tag>
        :                   <Tag color="error">error</Tag>,
    },
  ];

  return (
    <Modal
      open={open}
      onCancel={onClose}
      footer={null}
      title={
        <Space>
          <Tag color="blue">{bookmakerLabel(job.bookmaker)}</Tag>
          <Text strong>{job.controllerTitle ?? job.controllerId.slice(0, 8) + '…'}</Text>
          {job.username && <Text type="secondary">@{job.username}</Text>}
        </Space>
      }
      width={720}
    >
      {isLoading && <Text type="secondary">Загрузка…</Text>}
      {data && (
        <Space direction="vertical" style={{ width: '100%' }} size={16}>
          <Row gutter={16}>
            <Col span={6}><Statistic title="Интервал" value={`${data.job.pollIntervalSec} с`} /></Col>
            <Col span={6}><Statistic title="Следующий запуск" value={fmtNextRun(data.job)} /></Col>
            <Col span={6}><Statistic title="Последний запуск" value={fmtRelative(data.job.lastStartedAt)} /></Col>
            <Col span={6}><JobStatusTag job={data.job} /></Col>
          </Row>

          <div>
            <Text strong>Почасовая аналитика (24 ч)</Text>
            <div style={{ marginTop: 8 }}>
              <HourlyStatsChart controllerId={job.controllerId} />
            </div>
          </div>

          <div>
            <Text strong>Последние опросы</Text>
            <Table
              size="small"
              dataSource={data.pollHistory}
              columns={histCols}
              rowKey="startedAt"
              pagination={false}
              style={{ marginTop: 8 }}
              locale={{ emptyText: 'Опросов ещё не было' }}
            />
          </div>
        </Space>
      )}
    </Modal>
  );
}

// ── Config form ───────────────────────────────────────────────────────────────

const CONFIG_FIELDS = [
  {
    name: 'maxConcurrentTasks' as const,
    label: 'Макс. параллельных задач',
    tip: 'Глобальный семафор. Ограничивает одновременно выполняющихся опросов. При перегрузке задания откладываются с jitter, а не дропаются. Рекомендуется: 30–100.',
    min: 1, max: 500,
  },
  {
    name: 'defaultPollIntervalSec' as const,
    label: 'Интервал по умолчанию (с)',
    tip: 'Применяется если у контроллера не задан индивидуальный интервал. После изменения вступает в силу при следующем плановом запуске контроллера.',
    min: 5, max: 3600,
  },
  {
    name: 'fetchBudgetMs' as const,
    label: 'Бюджет HTTP-запроса (мс)',
    tip: 'Жёсткий таймаут на один запрос к парсеру букмекера. При превышении — запись "timeout" в историю, слот освобождается. Рекомендуется: 5000–10000.',
    min: 500, max: 60000,
  },
  {
    name: 'deferBaseMs' as const,
    label: 'Базовая задержка дефера (мс)',
    tip: 'Минимальная пауза при откладывании задания из-за насыщения пула. Суммируется со случайным jitter.',
    min: 50, max: 5000,
  },
  {
    name: 'deferJitterMs' as const,
    label: 'Макс. jitter дефера (мс)',
    tip: 'Максимальная случайная добавка к базовой задержке. Размывает "волны" повторных запусков, предотвращая thundering herd.',
    min: 0, max: 5000,
  },
  {
    name: 'defaultUserWeight' as const,
    label: 'DRR-вес по умолчанию',
    tip: 'В алгоритме Deficit Round Robin определяет, сколько заданий пользователь получает за один обход планировщика. Можно поднять VIP-пользователям.',
    min: 1, max: 100,
  },
];

function ConfigCard({
  config,
  onSave,
  saving,
}: {
  config: SchedulerConfig;
  onSave: (values: Partial<SchedulerConfig>) => void;
  saving: boolean;
}) {
  const [form] = Form.useForm<SchedulerConfig>();

  return (
    <Card
      title={<Space><SettingOutlined />Конфигурация</Space>}
      extra={
        <Tag color="orange">Изменения сохраняются в БД и выживают при перезапуске</Tag>
      }
    >
      <Form form={form} initialValues={config} layout="vertical" onFinish={onSave}>
        <Row gutter={16}>
          {CONFIG_FIELDS.map((f) => (
            <Col xs={24} sm={12} lg={8} key={f.name}>
              <Form.Item
                name={f.name}
                label={
                  <Space size={4}>
                    {f.label}
                    <Tooltip title={f.tip} overlayStyle={{ maxWidth: 320 }}>
                      <InfoCircleOutlined style={{ color: '#999', fontSize: 12 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={f.min} max={f.max} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          ))}
        </Row>
        <Button type="primary" htmlType="submit" loading={saving}>
          Сохранить
        </Button>
      </Form>
    </Card>
  );
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function SchedulerPage() {
  const qc = useQueryClient();
  const [selectedJob, setSelectedJob] = useState<ControllerJobDto | null>(null);
  const [msgApi, msgCtx] = message.useMessage();

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['scheduler-overview'],
    queryFn: schedulerApi.overview,
    refetchInterval: 5000,
  });

  const { mutate: saveConfig, isPending: saving } = useMutation({
    mutationFn: (vals: Partial<SchedulerConfig>) => schedulerApi.updateConfig(vals),
    onSuccess: () => {
      msgApi.success('Конфигурация сохранена в БД');
      qc.invalidateQueries({ queryKey: ['scheduler-overview'] });
    },
    onError: () => msgApi.error('Не удалось сохранить конфигурацию'),
  });

  const stats = data?.stats;

  const STAT_CARDS = [
    {
      key: 'Заданий в реестре',
      value: stats?.scheduledJobs ?? '—',
      color: undefined,
    },
    {
      key: 'В очереди (DelayQueue)',
      value: stats?.queueDepth ?? '—',
      color: stats && stats.queueDepth > 50 ? '#faad14' : undefined,
    },
    {
      key: 'Свободных слотов',
      value: stats ? `${stats.availableSlots} / ${stats.maxSlots}` : '—',
      color:
        stats && stats.availableSlots === 0
          ? '#ff4d4f'
          : stats && stats.availableSlots < stats.maxSlots * 0.2
          ? '#faad14'
          : '#52c41a',
    },
    {
      key: 'Задержка диспетчера p95',
      value: stats ? fmtMs(stats.lagP95Ms) : '—',
      color: stats && stats.lagP95Ms > 2000 ? '#faad14' : undefined,
    },
    {
      key: 'Голодание (макс.)',
      value: stats ? `${Math.round(stats.starvationSec)} с` : '—',
      color: stats && stats.starvationSec > 120 ? '#ff4d4f' : undefined,
    },
    {
      key: 'Дефер (всего)',
      value: stats?.tasksDeferred ?? '—',
      color: undefined,
    },
    {
      key: 'Событий обнаружено',
      value: stats?.eventsDetected ?? '—',
      color: undefined,
    },
    {
      key: 'Задача p95',
      value: stats ? fmtMs(stats.taskDurP95Ms) : '—',
      color: stats && stats.taskDurP95Ms > 5000 ? '#faad14' : undefined,
    },
  ];

  const inFlightCount = data?.jobs.filter((j) => j.inFlight).length ?? 0;
  const lateCount     = data?.jobs.filter((j) => j.status === 'LATE').length ?? 0;

  const jobCols = [
    {
      title: 'Букмекер / Контроллер',
      key: 'controller',
      width: 200,
      render: (_: unknown, row: ControllerJobDto) => (
        <Space direction="vertical" size={2}>
          <Space size={4}>
            <Tag color="blue" style={{ margin: 0 }}>{bookmakerLabel(row.bookmaker)}</Tag>
            <Text style={{ fontSize: 12 }}>
              {row.controllerTitle ?? <Text code style={{ fontSize: 11 }}>{row.controllerId.slice(0, 8)}…</Text>}
            </Text>
          </Space>
          {row.username && (
            <Text type="secondary" style={{ fontSize: 11 }}>@{row.username}</Text>
          )}
        </Space>
      ),
    },
    {
      title: 'Интервал',
      dataIndex: 'pollIntervalSec',
      width: 80,
      render: (v: number) => `${v} с`,
    },
    {
      title: (
        <Tooltip title="Когда задание должно запуститься следующий раз">
          Следующий запуск
        </Tooltip>
      ),
      key: 'nextRun',
      width: 140,
      render: (_: unknown, row: ControllerJobDto) => (
        <Tooltip title={`nextRunAt: ${row.nextRunAt}`}>
          <Text>{fmtNextRun(row)}</Text>
        </Tooltip>
      ),
    },
    {
      title: 'Последний запуск',
      dataIndex: 'lastStartedAt',
      width: 130,
      render: (v: string | null) => (
        <Tooltip title={v ?? 'Ещё не запускался'}>
          {fmtRelative(v)}
        </Tooltip>
      ),
    },
    {
      title: 'Статус',
      key: 'status',
      width: 160,
      render: (_: unknown, row: ControllerJobDto) => <JobStatusTag job={row} />,
    },
    {
      title: '',
      key: 'actions',
      width: 40,
      render: (_: unknown, row: ControllerJobDto) => (
        <Button
          size="small"
          type="text"
          icon={<InfoCircleOutlined />}
          onClick={() => setSelectedJob(row)}
        />
      ),
    },
  ];

  return (
    <>
      {msgCtx}
      <Space direction="vertical" size={16} style={{ width: '100%' }}>

        {/* Header */}
        <Row justify="space-between" align="middle">
          <Col>
            <Title level={4} style={{ margin: 0 }}>
              <ClockCircleOutlined /> Планировщик мониторинга
            </Title>
            <Text type="secondary" style={{ fontSize: 12 }}>
              Авто-обновление: карточки и задания — каждые 5 с, график — каждые 30 с
            </Text>
          </Col>
          <Col>
            <Button
              icon={<ReloadOutlined spin={isFetching} />}
              onClick={() => qc.invalidateQueries({ queryKey: ['scheduler-overview'] })}
            >
              Обновить
            </Button>
          </Col>
        </Row>

        {lateCount > 0 && (
          <Alert
            type="warning"
            showIcon
            message={`${lateCount} задани${lateCount === 1 ? 'е просрочено' : 'й просрочено'} — возможная перегрузка диспетчера`}
          />
        )}

        {/* Stat cards */}
        <Row gutter={[12, 12]}>
          {STAT_CARDS.map((s) => (
            <Col xs={12} sm={8} md={6} xl={3} key={s.key}>
              <Tooltip
                title={STAT_TIPS[s.key]}
                placement="bottom"
                overlayStyle={{ maxWidth: 300 }}
              >
                <Card size="small" loading={isLoading} style={{ cursor: 'default' }}>
                  <Statistic
                    title={
                      <Text style={{ fontSize: 11 }}>
                        {s.key}
                        <InfoCircleOutlined style={{ marginLeft: 4, color: '#bbb', fontSize: 10 }} />
                      </Text>
                    }
                    value={s.value}
                    valueStyle={s.color ? { color: s.color, fontSize: 18 } : { fontSize: 18 }}
                  />
                </Card>
              </Tooltip>
            </Col>
          ))}
        </Row>

        {/* p50/p95/p99 row */}
        {stats && (
          <Card size="small" title="Гистограммы (накопленные с последнего старта)">
            <Row gutter={32} wrap>
              <Col>
                <Text type="secondary">Задача p50 / p95 / p99: </Text>
                <Text strong>
                  {fmtMs(stats.taskDurP50Ms)} / {fmtMs(stats.taskDurP95Ms)} / {fmtMs(stats.taskDurP99Ms)}
                </Text>
              </Col>
              <Col>
                <Text type="secondary">Lag диспетчера p50 / p95 / p99: </Text>
                <Text strong>
                  {fmtMs(stats.lagP50Ms)} / {fmtMs(stats.lagP95Ms)} / {fmtMs(stats.lagP99Ms)}
                </Text>
              </Col>
              <Col>
                <Text type="secondary">Пропущено (skipped): </Text>
                <Text strong style={{ color: stats.tasksSkipped > 0 ? '#ff4d4f' : undefined }}>
                  {stats.tasksSkipped}
                </Text>
                {stats.tasksSkipped === 0 && (
                  <Text type="secondary" style={{ marginLeft: 4, fontSize: 11 }}>(норма при DRR)</Text>
                )}
              </Col>
            </Row>
          </Card>
        )}

        {/* Metrics chart */}
        <MetricsChart />

        {/* Config */}
        {data?.config && (
          <ConfigCard config={data.config} onSave={saveConfig} saving={saving} />
        )}

        {/* Jobs table */}
        <Card
          title={
            <Space>
              <ClockCircleOutlined />
              Активные задания
              {inFlightCount > 0 && <Badge count={inFlightCount} color="blue" title="выполняется" />}
              {lateCount > 0     && <Badge count={lateCount}     color="orange" title="просрочено" />}
            </Space>
          }
          size="small"
        >
          <Table<ControllerJobDto>
            dataSource={data?.jobs ?? []}
            columns={jobCols}
            rowKey="controllerId"
            loading={isLoading}
            size="small"
            onRow={(row) => ({ onClick: () => setSelectedJob(row), style: { cursor: 'pointer' } })}
            pagination={{ pageSize: 20, showSizeChanger: true, showTotal: (t) => `${t} заданий` }}
            rowClassName={(row) =>
              row.inFlight        ? 'row-inflight'
              : row.status === 'LATE' ? 'row-late'
              : ''
            }
          />
        </Card>
      </Space>

      {/* Job detail modal */}
      {selectedJob && (
        <JobDetailModal
          job={selectedJob}
          open={!!selectedJob}
          onClose={() => setSelectedJob(null)}
        />
      )}

      <style>{`
        .row-inflight > td { background: rgba(22, 119, 255, 0.05) !important; }
        .row-late     > td { background: rgba(250, 173, 20,  0.07) !important; }
      `}</style>
    </>
  );
}
