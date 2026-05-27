import {
  LineChart, Line, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, ReferenceLine,
} from 'recharts'
import type { BalancePoint } from '../api/types'

interface Props {
  data: BalancePoint[]
}

export default function BalanceDynamicsChart({ data }: Props) {
  if (data.length === 0) {
    return <div style={emptyStyle}>Нет завершённых ставок за период</div>
  }

  const fmt = (v: number) =>
    v >= 0 ? `+${v.toFixed(0)}` : v.toFixed(0)

  return (
    <ResponsiveContainer width="100%" height={220}>
      <LineChart data={data} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--tg-theme-hint-color, #ccc)" strokeOpacity={0.3} />
        <XAxis dataKey="date" tick={{ fontSize: 11 }} tickLine={false} axisLine={false}
               tickFormatter={(d: string) => d.slice(5)} />
        <YAxis tick={{ fontSize: 11 }} tickLine={false} axisLine={false}
               tickFormatter={fmt} width={50} />
        <Tooltip formatter={(v: number) => [fmt(v), 'Накопленный P&L']}
                 labelFormatter={(l: string) => `Дата: ${l}`}
                 contentStyle={{ fontSize: 12 }} />
        <ReferenceLine y={0} stroke="var(--tg-theme-hint-color, #ccc)" strokeDasharray="4 2" />
        <Line type="monotone" dataKey="cumulative" stroke="#3b82f6" strokeWidth={2}
              dot={data.length < 30} activeDot={{ r: 4 }} />
      </LineChart>
    </ResponsiveContainer>
  )
}

const emptyStyle: React.CSSProperties = {
  textAlign: 'center', padding: '40px 16px',
  color: 'var(--tg-theme-hint-color, #888)', fontSize: 14,
}
