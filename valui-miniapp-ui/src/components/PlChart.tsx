import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, Cell, ReferenceLine,
} from 'recharts'
import type { BetPlPoint } from '../api/types'

interface Props {
  data: BetPlPoint[]
}

const STATUS_COLOR: Record<string, string> = {
  WON:      '#22c55e',
  LOST:     '#ef4444',
  RETURNED: '#94a3b8',
  OPEN:     '#3b82f6',
}

export default function PlChart({ data }: Props) {
  const resolved = data.filter(b => b.status !== 'CANCELLED')

  if (resolved.length === 0) {
    return <div style={emptyStyle}>Нет ставок за период</div>
  }

  const fmt = (v: number) =>
    v >= 0 ? `+${v.toFixed(0)}` : v.toFixed(0)

  return (
    <ResponsiveContainer width="100%" height={220}>
      <BarChart data={resolved} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--tg-theme-hint-color, #ccc)" strokeOpacity={0.3} />
        <XAxis dataKey="date" tick={{ fontSize: 10 }} tickLine={false} axisLine={false}
               tickFormatter={(d: string) => d.slice(5)} />
        <YAxis tick={{ fontSize: 11 }} tickLine={false} axisLine={false}
               tickFormatter={fmt} width={50} />
        <Tooltip
          formatter={(v: number, _name: string, props: { payload?: BetPlPoint }) => [
            fmt(v),
            props.payload?.title ?? 'P&L',
          ]}
          labelFormatter={(l: string) => `Дата: ${l}`}
          contentStyle={{ fontSize: 12 }}
        />
        <ReferenceLine y={0} stroke="var(--tg-theme-hint-color, #ccc)" />
        <Bar dataKey="pnl" radius={[3, 3, 0, 0]}>
          {resolved.map((entry, i) => (
            <Cell key={i} fill={STATUS_COLOR[entry.status] ?? '#94a3b8'} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}

const emptyStyle: React.CSSProperties = {
  textAlign: 'center', padding: '40px 16px',
  color: 'var(--tg-theme-hint-color, #888)', fontSize: 14,
}
