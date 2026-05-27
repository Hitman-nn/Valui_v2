import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, Cell, ReferenceLine, Brush,
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

const STATUS_RU: Record<string, string> = {
  WON: 'Победа', LOST: 'Поражение', RETURNED: 'Возврат', OPEN: 'Открытые',
}

function fmt(v: number) { return v >= 0 ? `+${v.toFixed(0)}` : v.toFixed(0) }

function PlTooltip({ active, payload }: any) {
  if (!active || !payload?.length) return null
  const b: BetPlPoint = payload[0].payload
  return (
    <div style={ttStyle}>
      <div style={{ fontWeight: 600, marginBottom: 4, lineHeight: 1.3 }}>{b.title}</div>
      <div>Дата: {b.date}</div>
      <div>Ставка: {b.stake.toFixed(0)}</div>
      <div>Коэф: ×{b.odds.toFixed(2)}</div>
      <div>P&L: <span style={{ color: STATUS_COLOR[b.status], fontWeight: 600 }}>{fmt(b.pnl)}</span></div>
      <div>Статус: {STATUS_RU[b.status] ?? b.status}</div>
    </div>
  )
}

const ttStyle: React.CSSProperties = {
  background: 'var(--tg-theme-bg-color, #fff)',
  border: '1px solid var(--tg-theme-hint-color, #eee)',
  borderRadius: 8, padding: '8px 12px', fontSize: 12, maxWidth: 200,
}

export default function PlChart({ data }: Props) {
  const resolved = data.filter(b => b.status !== 'CANCELLED')

  if (resolved.length === 0) {
    return <div style={emptyStyle}>Нет ставок за период</div>
  }

  const showBrush = resolved.length > 15

  return (
    <ResponsiveContainer width="100%" height={showBrush ? 280 : 240}>
      <BarChart data={resolved} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--tg-theme-hint-color, #ccc)" strokeOpacity={0.3} />
        <XAxis dataKey="date" tick={{ fontSize: 10 }} tickLine={false} axisLine={false}
               tickFormatter={(d: string) => d.slice(5)} />
        <YAxis tick={{ fontSize: 11 }} tickLine={false} axisLine={false}
               tickFormatter={fmt} width={52} />
        <Tooltip content={<PlTooltip />} />
        <ReferenceLine y={0} stroke="var(--tg-theme-hint-color, #ccc)" />
        <Bar dataKey="pnl" radius={[3, 3, 0, 0]}>
          {resolved.map((entry, i) => (
            <Cell key={i} fill={STATUS_COLOR[entry.status] ?? '#94a3b8'} />
          ))}
        </Bar>
        {showBrush && (
          <Brush dataKey="date" height={20} travellerWidth={8}
                 fill="var(--tg-theme-secondary-bg-color, #f8f8f8)"
                 stroke="var(--tg-theme-hint-color, #ccc)"
                 tickFormatter={(d: string) => d.slice(5)} />
        )}
      </BarChart>
    </ResponsiveContainer>
  )
}

const emptyStyle: React.CSSProperties = {
  textAlign: 'center', padding: '40px 16px',
  color: 'var(--tg-theme-hint-color, #888)', fontSize: 14,
}
