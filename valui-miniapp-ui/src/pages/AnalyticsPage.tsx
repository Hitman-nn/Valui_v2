import { useEffect, useState } from 'react'
import dayjs from 'dayjs'
import api from '../api/client'
import type {
  AnalyticsResponse, AnalyticsScope, BetAccountDto, BetPersonDto, DateRange,
} from '../api/types'
import FilterBar from '../components/FilterBar'
import BalanceDynamicsChart from '../components/BalanceDynamicsChart'
import PlChart from '../components/PlChart'
import DistributionCharts from '../components/DistributionCharts'

const TABS = ['Баланс', 'P&L', 'Распределение'] as const
type Tab = typeof TABS[number]

interface Props {
  chatId: number
}

export default function AnalyticsPage({ chatId }: Props) {
  const [accounts, setAccounts] = useState<BetAccountDto[]>([])
  const [persons,  setPersons]  = useState<BetPersonDto[]>([])
  const [scope,       setScope]       = useState<AnalyticsScope>('ACCOUNT')
  const [selectedId,  setSelectedId]  = useState('')
  const [dateRange,   setDateRange]   = useState<DateRange>('month')
  const [customFrom,  setCustomFrom]  = useState('')
  const [customTo,    setCustomTo]    = useState('')
  const [activeTab,   setActiveTab]   = useState<Tab>('Баланс')
  const [data,        setData]        = useState<AnalyticsResponse | null>(null)
  const [loading,     setLoading]     = useState(false)
  const [error,       setError]       = useState<string | null>(null)

  // Load accounts + persons once
  useEffect(() => {
    const params = { chatId }
    Promise.all([
      api.get<BetAccountDto[]>('/accounts', { params }),
      api.get<BetPersonDto[]>('/persons',  { params }),
    ]).then(([a, p]) => {
      setAccounts(a.data)
      setPersons(p.data)
    }).catch(() => setError('Не удалось загрузить данные'))
  }, [chatId])

  // Load analytics when selection changes
  useEffect(() => {
    if (!selectedId) { setData(null); return }

    const { from, to } = resolveDates(dateRange, customFrom, customTo)
    if (dateRange === 'custom' && (!from || !to)) return

    setLoading(true)
    setError(null)
    api.get<AnalyticsResponse>('/analytics', {
      params: { chatId, scope, id: selectedId, from, to },
    }).then(r => {
      setData(r.data)
    }).catch(() => {
      setError('Ошибка загрузки аналитики')
    }).finally(() => setLoading(false))
  }, [chatId, scope, selectedId, dateRange, customFrom, customTo])

  const summary = data?.summary

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', overflow: 'hidden' }}>
      <FilterBar
        accounts={accounts} persons={persons}
        scope={scope} selectedId={selectedId}
        dateRange={dateRange} customFrom={customFrom} customTo={customTo}
        onScopeChange={setScope} onSelectedIdChange={setSelectedId}
        onDateRangeChange={setDateRange}
        onCustomFromChange={setCustomFrom} onCustomToChange={setCustomTo}
      />

      {/* Summary chips */}
      {summary && (
        <div style={summaryStyle}>
          <Chip label="Ставок" value={String(summary.totalBets)} />
          <Chip label="P&L" value={fmtPnl(summary.totalPnl)} color={summary.totalPnl >= 0 ? '#22c55e' : '#ef4444'} />
          <Chip label="ROI" value={`${summary.roi >= 0 ? '+' : ''}${summary.roi.toFixed(1)}%`}
                color={summary.roi >= 0 ? '#22c55e' : '#ef4444'} />
          <Chip label="W/L" value={`${summary.wonBets}/${summary.lostBets}`} />
        </div>
      )}

      {/* Tabs */}
      <div style={tabBarStyle}>
        {TABS.map(t => (
          <button key={t} onClick={() => setActiveTab(t)} style={{
            ...tabBtnStyle,
            borderBottom: t === activeTab ? '2px solid var(--tg-theme-button-color, #3b82f6)' : '2px solid transparent',
            color: t === activeTab ? 'var(--tg-theme-button-color, #3b82f6)' : 'var(--tg-theme-hint-color, #888)',
          }}>
            {t}
          </button>
        ))}
      </div>

      {/* Content */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '8px 16px 24px' }}>
        {!selectedId && (
          <div style={hintStyle}>Выберите счёт или участника для отображения аналитики</div>
        )}
        {loading && <div style={hintStyle}>Загрузка...</div>}
        {error && <div style={{ ...hintStyle, color: '#ef4444' }}>{error}</div>}

        {data && !loading && (
          <>
            {activeTab === 'Баланс' && <BalanceDynamicsChart data={data.balanceDynamics} />}
            {activeTab === 'P&L'    && <PlChart data={data.plPoints} />}
            {activeTab === 'Распределение' && <DistributionCharts data={data.distribution} />}
          </>
        )}
      </div>
    </div>
  )
}

function Chip({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div style={{ textAlign: 'center', minWidth: 60 }}>
      <div style={{ fontSize: 10, color: 'var(--tg-theme-hint-color, #888)', marginBottom: 2 }}>{label}</div>
      <div style={{ fontSize: 14, fontWeight: 700, color: color || 'var(--tg-theme-text-color, #000)' }}>{value}</div>
    </div>
  )
}

function resolveDates(range: DateRange, customFrom: string, customTo: string) {
  const now = dayjs()
  if (range === 'week')   return { from: now.subtract(7, 'day').toISOString(),   to: now.toISOString() }
  if (range === 'month')  return { from: now.subtract(30, 'day').toISOString(),  to: now.toISOString() }
  if (range === 'custom') return {
    from: customFrom ? dayjs(customFrom).startOf('day').toISOString() : '',
    to:   customTo   ? dayjs(customTo).endOf('day').toISOString()     : '',
  }
  return { from: undefined, to: undefined }  // all time
}

function fmtPnl(v: number) {
  return (v >= 0 ? '+' : '') + v.toFixed(0)
}

const summaryStyle: React.CSSProperties = {
  display: 'flex', justifyContent: 'space-around', padding: '10px 16px',
  borderBottom: '1px solid var(--tg-theme-hint-color, #eee)',
}
const tabBarStyle: React.CSSProperties = {
  display: 'flex', borderBottom: '1px solid var(--tg-theme-hint-color, #eee)',
}
const tabBtnStyle: React.CSSProperties = {
  flex: 1, padding: '10px 0', background: 'none', border: 'none',
  cursor: 'pointer', fontSize: 13, fontWeight: 500, transition: 'color 0.15s',
}
const hintStyle: React.CSSProperties = {
  textAlign: 'center', padding: '40px 16px',
  color: 'var(--tg-theme-hint-color, #888)', fontSize: 14,
}
