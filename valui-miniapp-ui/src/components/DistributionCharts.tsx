import {
  PieChart, Pie, Cell, Tooltip, Legend,
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

const BUCKET_COLOR = '#6366f1'

export default function DistributionCharts({ data }: Props) {
  const outcomeEntries = Object.entries(data.byOutcome).map(([name, value]) => ({ name, value }))

  return (
    <div>
      {/* Outcomes donut */}
      <SectionTitle>Исходы</SectionTitle>
      {outcomeEntries.length > 0 ? (
        <ResponsiveContainer width="100%" height={180}>
          <PieChart>
            <Pie data={outcomeEntries} dataKey="value" nameKey="name"
                 cx="50%" cy="50%" innerRadius={50} outerRadius={75} paddingAngle={3}>
              {outcomeEntries.map((e, i) => (
                <Cell key={i} fill={OUTCOME_COLORS[e.name] ?? '#94a3b8'} />
              ))}
            </Pie>
            <Tooltip formatter={(v: number, name: string) => [v, labelRu(name)]} />
            <Legend formatter={labelRu} iconType="circle" iconSize={10}
                    wrapperStyle={{ fontSize: 12 }} />
          </PieChart>
        </ResponsiveContainer>
      ) : <Empty />}

      {/* Stake distribution */}
      <SectionTitle>Суммы ставок</SectionTitle>
      {data.byStake.length > 0 ? (
        <ResponsiveContainer width="100%" height={140}>
          <BarChart data={data.byStake} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" strokeOpacity={0.3} />
            <XAxis dataKey="label" tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
            <YAxis tick={{ fontSize: 11 }} tickLine={false} axisLine={false} allowDecimals={false} width={30} />
            <Tooltip />
            <Bar dataKey="count" fill={BUCKET_COLOR} name="Ставок" radius={[3, 3, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      ) : <Empty />}

      {/* Odds distribution */}
      <SectionTitle>Коэффициенты</SectionTitle>
      {data.byOdds.length > 0 ? (
        <ResponsiveContainer width="100%" height={140}>
          <BarChart data={data.byOdds} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" strokeOpacity={0.3} />
            <XAxis dataKey="label" tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
            <YAxis tick={{ fontSize: 11 }} tickLine={false} axisLine={false} allowDecimals={false} width={30} />
            <Tooltip />
            <Bar dataKey="count" fill="#8b5cf6" name="Ставок" radius={[3, 3, 0, 0]} />
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

const RU: Record<string, string> = {
  WON: 'Победа', LOST: 'Поражение', RETURNED: 'Возврат', OPEN: 'Открытые',
}
function labelRu(name: string) { return RU[name] ?? name }
