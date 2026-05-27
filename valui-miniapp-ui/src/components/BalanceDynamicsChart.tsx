import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, ReferenceLine, Brush,
} from 'recharts'
import type { BalancePoint } from '../api/types'

interface Props {
  data: BalancePoint[]
}

function fmt(v: number) { return v >= 0 ? `+${v.toFixed(0)}` : v.toFixed(0) }

function BalanceTooltip({ active, payload, label }: any) {
  if (!active || !payload?.length) return null
  const pt: BalancePoint = payload[0].payload
  return (
    <div style={ttStyle}>
      <div style={{ fontWeight: 600, marginBottom: 4 }}>{label}</div>
      <div>P&L дня: <span style={{ fontWeight: 600 }}>{fmt(pt.pnl)}</span></div>
      <div>Накоплено: <span style={{ fontWeight: 600 }}>{fmt(pt.cumulative)}</span></div>
    </div>
  )
}

export default function BalanceDynamicsChart({ data }: Props) {
  if (data.length === 0) {
    return <div style={emptyStyle}>Нет завершённых ставок за период</div>
  }

  const last  = data[data.length - 1].cumulative
  const color = last >= 0 ? '#22c55e' : '#ef4444'
  const showBrush = data.length > 15

  return (
    <ResponsiveContainer width="100%" height={showBrush ? 280 : 240}>
      <AreaChart data={data} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
        <defs>
          <linearGradient id="balGrad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%"  stopColor={color} stopOpacity={0.3} />
            <stop offset="95%" stopColor={color} stopOpacity={0.02} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--tg-theme-hint-color, #ccc)" strokeOpacity={0.3} />
        <XAxis dataKey="date" tick={{ fontSize: 11 }} tickLine={false} axisLine={false}
               tickFormatter={(d: string) => d.slice(5)} />
        <YAxis tick={{ fontSize: 11 }} tickLine={false} axisLine={false}
               tickFormatter={fmt} width={52} />
        <Tooltip content={<BalanceTooltip />} />
        <ReferenceLine y={0} stroke="var(--tg-theme-hint-color, #ccc)" strokeDasharray="4 2" />
        <Area type="monotone" dataKey="cumulative" stroke={color} strokeWidth={2}
              fill="url(#balGrad)" dot={data.length < 20} activeDot={{ r: 4 }} />
        {showBrush && (
          <Brush dataKey="date" height={20} travellerWidth={8}
                 fill="var(--tg-theme-secondary-bg-color, #f8f8f8)"
                 stroke="var(--tg-theme-hint-color, #ccc)"
                 tickFormatter={(d: string) => d.slice(5)} />
        )}
      </AreaChart>
    </ResponsiveContainer>
  )
}

const ttStyle: React.CSSProperties = {
  background: 'var(--tg-theme-bg-color, #fff)',
  border: '1px solid var(--tg-theme-hint-color, #eee)',
  borderRadius: 8, padding: '8px 12px', fontSize: 12,
}

const emptyStyle: React.CSSProperties = {
  textAlign: 'center', padding: '40px 16px',
  color: 'var(--tg-theme-hint-color, #888)', fontSize: 14,
}
