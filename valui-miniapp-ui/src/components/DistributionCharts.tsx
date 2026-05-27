import {
  PieChart, Pie, Cell, Tooltip, Legend, Label,
  BarChart, Bar, XAxis, YAxis, CartesianGrid, ResponsiveContainer,
} from 'recharts'
import type { DistributionData } from '../api/types'

interface Props {
  data: DistributionData
}

const OUTCOME_COLORS: Record<string, string> = {
  WON:      '#22c55e',
  LOST:     '#ef4444',
  RETURNED: '#94a3b8',
  OPEN:     '#3b82f6',
}

const RU: Record<string, string> = {
  WON: 'Победа', LOST: 'Поражение', RETURNED: 'Возврат', OPEN: 'Открытые',
}
function labelRu(name: string) { return RU[name] ?? name }

export default function DistributionCharts({ data }: Props) {
  const outcomeEntries = Object.entries(data.byOutcome).map(([name, value]) => ({ name, value: value as number }))
  const total    = outcomeEntries.reduce((s, e) => s + e.value, 0)
  const won      = (data.byOutcome['WON'] ?? 0) as number
  const lost     = (data.byOutcome['LOST'] ?? 0) as number
  const resolved = won + lost
  const winRate  = resolved > 0 ? Math.round(won / resolved * 100) : null

  const renderCenter = (props: any) => {
    const { cx, cy } = props.viewBox ?? {}
    if (cx == null || cy == null) return null
    return (
      <g>
        <text x={cx} y={cy - 7} textAnchor="middle"
              style={{ fontSize: 18, fontWeight: 700, fill: 'var(--tg-theme-text-color, #000)' }}>
          {total}
        </text>
        <text x={cx} y={cy + 9} textAnchor="middle"
              style={{ fontSize: 10, fill: 'var(--tg-theme-hint-color, #888)' }}>
          ставок
        </text>
        {winRate !== null && (
          <text x={cx} y={cy + 24} textAnchor="middle"
                style={{ fontSize: 11, fontWeight: 600, fill: winRate >= 50 ? '#22c55e' : '#ef4444' }}>
            {winRate}% W
          </text>
        )}
      </g>
    )
  }

  return (
    <div>
      <SectionTitle>Исходы</SectionTitle>
      {outcomeEntries.length > 0 ? (
        <ResponsiveContainer width="100%" height={190}>
          <PieChart>
            <Pie data={outcomeEntries} dataKey="value" nameKey="name"
                 cx="50%" cy="50%" innerRadius={52} outerRadius={78} paddingAngle={3}>
              {outcomeEntries.map((e, i) => (
                <Cell key={i} fill={OUTCOME_COLORS[e.name] ?? '#94a3b8'} />
              ))}
              <Label content={renderCenter} position="center" />
            </Pie>
            <Tooltip formatter={(v: number, name: string) => [
              `${v} (${total > 0 ? Math.round(v / total * 100) : 0}%)`,
              labelRu(name),
            ]} />
            <Legend formatter={labelRu} iconType="circle" iconSize={10}
                    wrapperStyle={{ fontSize: 12 }} />
          </PieChart>
        </ResponsiveContainer>
      ) : <Empty />}

      <SectionTitle>Суммы ставок</SectionTitle>
      {data.byStake.length > 0 ? (
        <ResponsiveContainer width="100%" height={150}>
          <BarChart data={data.byStake} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" strokeOpacity={0.3} />
            <XAxis dataKey="label" tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
            <YAxis tick={{ fontSize: 11 }} tickLine={false} axisLine={false} allowDecimals={false} width={30} />
            <Tooltip formatter={(v: number) => [v, 'Ставок']} />
            <Bar dataKey="count" fill="#6366f1" name="Ставок" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      ) : <Empty />}

      <SectionTitle>Коэффициенты</SectionTitle>
      {data.byOdds.length > 0 ? (
        <ResponsiveContainer width="100%" height={150}>
          <BarChart data={data.byOdds} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" strokeOpacity={0.3} />
            <XAxis dataKey="label" tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
            <YAxis tick={{ fontSize: 11 }} tickLine={false} axisLine={false} allowDecimals={false} width={30} />
            <Tooltip formatter={(v: number) => [v, 'Ставок']} />
            <Bar dataKey="count" fill="#8b5cf6" name="Ставок" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      ) : <Empty />}
    </div>
  )
}

function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <div style={{ fontSize: 13, fontWeight: 600, margin: '12px 0 4px',
                  color: 'var(--tg-theme-hint-color, #666)' }}>
      {children}
    </div>
  )
}

function Empty() {
  return (
    <div style={{ textAlign: 'center', padding: '16px', fontSize: 13,
                  color: 'var(--tg-theme-hint-color, #888)' }}>
      Нет данных
    </div>
  )
}
