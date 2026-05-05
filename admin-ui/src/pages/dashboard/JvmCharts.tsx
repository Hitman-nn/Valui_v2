import { useState } from 'react';
import { Card, Row, Col, Segmented, Spin, Typography, theme as antTheme } from 'antd';
import {
  AreaChart, Area,
  LineChart, Line,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { dashboardApi } from '../../api/endpoints';
import type { JvmDataPoint } from '../../api/types';

type Range = '1h' | '24h' | '7d' | '30d';

const RANGES: { label: string; value: Range }[] = [
  { label: '1 ч',    value: '1h'  },
  { label: '24 ч',   value: '24h' },
  { label: '7 дней', value: '7d'  },
  { label: '30 дней', value: '30d' },
];

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type AnyFn = (...args: any[]) => any;

function fmt(ts: number, range: Range): string {
  const d = dayjs(ts);
  if (range === '1h')  return d.format('HH:mm');
  if (range === '24h') return d.format('HH:mm');
  if (range === '7d')  return d.format('DD.MM HH:mm');
  return d.format('DD.MM');
}

function downsample(data: JvmDataPoint[], maxPoints: number): JvmDataPoint[] {
  if (data.length <= maxPoints) return data;
  const step = Math.ceil(data.length / maxPoints);
  return data.filter((_, i) => i % step === 0);
}

export default function JvmCharts() {
  const [range, setRange] = useState<Range>('1h');
  const { token } = antTheme.useToken();

  const { data: raw = [], isLoading } = useQuery({
    queryKey: ['jvm-history', range],
    queryFn: () => dashboardApi.jvmHistory(range),
    refetchInterval: 60_000,
  });

  const data = downsample(raw, 300);

  const tickFormatter = (ts: number) => fmt(ts, range);
  const labelFormatter: AnyFn = (ts: number) => fmt(ts, range);

  const gridColor = token.colorBorderSecondary;
  const textColor = token.colorTextSecondary;

  if (isLoading && data.length === 0) {
    return (
      <Card title="История JVM-метрик" size="small">
        <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}>
          <Spin />
        </div>
      </Card>
    );
  }

  if (data.length === 0) {
    return (
      <Card title="История JVM-метрик" size="small">
        <Typography.Text type="secondary">
          Данные накапливаются — первые точки появятся через минуту.
        </Typography.Text>
      </Card>
    );
  }

  const chartProps = {
    data,
    margin: { top: 4, right: 8, left: 0, bottom: 0 },
  };

  const axisProps = {
    tick: { fontSize: 11, fill: textColor },
    stroke: gridColor,
  };

  return (
    <Card
      title="История JVM-метрик"
      size="small"
      extra={
        <Segmented
          size="small"
          value={range}
          onChange={(v) => setRange(v as Range)}
          options={RANGES}
        />
      }
    >
      <Row gutter={[16, 16]}>
        {/* Heap */}
        <Col xs={24} lg={12}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>Heap (МБ)</Typography.Text>
          <ResponsiveContainer width="100%" height={160}>
            <AreaChart {...chartProps}>
              <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
              <XAxis dataKey="ts" tickFormatter={tickFormatter} {...axisProps} />
              <YAxis {...axisProps} width={40} />
              <Tooltip
                labelFormatter={labelFormatter}
                formatter={((v: number, name: string) => [`${v} МБ`, name]) as AnyFn}
                contentStyle={{ fontSize: 12 }}
              />
              <Legend wrapperStyle={{ fontSize: 12 }} />
              <Area
                type="monotone" dataKey="heapMaxMb" name="Max"
                stroke="#8c8c8c" fill="transparent" strokeDasharray="4 2"
                dot={false} isAnimationActive={false}
              />
              <Area
                type="monotone" dataKey="heapMb" name="Used"
                stroke="#1668dc" fill="#1668dc" fillOpacity={0.15}
                dot={false} isAnimationActive={false}
              />
            </AreaChart>
          </ResponsiveContainer>
        </Col>

        {/* CPU */}
        <Col xs={24} lg={12}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>CPU (%)</Typography.Text>
          <ResponsiveContainer width="100%" height={160}>
            <LineChart {...chartProps}>
              <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
              <XAxis dataKey="ts" tickFormatter={tickFormatter} {...axisProps} />
              <YAxis {...axisProps} width={40} domain={[0, 100]} />
              <Tooltip
                labelFormatter={labelFormatter}
                formatter={((v: number) => [`${v.toFixed(1)}%`, 'CPU']) as AnyFn}
                contentStyle={{ fontSize: 12 }}
              />
              <Line
                type="monotone" dataKey="cpu" name="CPU %"
                stroke="#f5222d" dot={false} strokeWidth={1.5}
                isAnimationActive={false}
              />
            </LineChart>
          </ResponsiveContainer>
        </Col>

        {/* Non-Heap */}
        <Col xs={24} lg={12}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>Non-Heap (МБ)</Typography.Text>
          <ResponsiveContainer width="100%" height={160}>
            <AreaChart {...chartProps}>
              <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
              <XAxis dataKey="ts" tickFormatter={tickFormatter} {...axisProps} />
              <YAxis {...axisProps} width={40} />
              <Tooltip
                labelFormatter={labelFormatter}
                formatter={((v: number) => [`${v} МБ`, 'Non-Heap']) as AnyFn}
                contentStyle={{ fontSize: 12 }}
              />
              <Area
                type="monotone" dataKey="nonHeapMb" name="Non-Heap"
                stroke="#722ed1" fill="#722ed1" fillOpacity={0.15}
                dot={false} strokeWidth={1.5} isAnimationActive={false}
              />
            </AreaChart>
          </ResponsiveContainer>
        </Col>

        {/* Threads */}
        <Col xs={24} lg={12}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>Потоки</Typography.Text>
          <ResponsiveContainer width="100%" height={160}>
            <LineChart {...chartProps}>
              <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
              <XAxis dataKey="ts" tickFormatter={tickFormatter} {...axisProps} />
              <YAxis {...axisProps} width={40} />
              <Tooltip
                labelFormatter={labelFormatter}
                formatter={((v: number) => [v, 'Потоков']) as AnyFn}
                contentStyle={{ fontSize: 12 }}
              />
              <Line
                type="monotone" dataKey="threads" name="Потоков"
                stroke="#fa8c16" dot={false} strokeWidth={1.5}
                isAnimationActive={false}
              />
            </LineChart>
          </ResponsiveContainer>
        </Col>
      </Row>
    </Card>
  );
}
