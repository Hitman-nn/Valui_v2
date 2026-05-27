import { useEffect, useState } from 'react'
import dayjs from 'dayjs'
import api from '../api/client'
import type { AnalyticsResponse, BetAccountDto, BetPersonDto, DateRange } from '../api/types'
import FilterBar from '../components/FilterBar'
import BalanceDynamicsChart from '../components/BalanceDynamicsChart'
import PlChart from '../components/PlChart'
import DistributionCharts from '../components/DistributionCharts'

const TABS = ['Баланс', 'P&L', 'Распределение'] as const
type Tab = typeof TABS[number]

export default function AnalyticsPage() {
  const [accounts,        setAccounts]        = useState<BetAccountDto[]>([])
  const [persons,         setPersons]         = useState<BetPersonDto[]>([])
  const [selectedAccIds,  setSelectedAccIds]  = useState<Set<string>>(new Set())
  const [selectedPerIds,  setSelectedPerIds]  = useState<Set<string>>(new Set())
  const [dateRange,       setDateRange]       = useState<DateRange>('month')
  const [customFrom,      setCustomFrom]      = useState('')
  const [customTo,        setCustomTo]        = useState('')
  const [activeTab,       setActiveTab]       = useState<Tab>('Баланс')
  const [data,            setData]            = useState<AnalyticsResponse | null>(null)
  const [loading,         setLoading]         = useState(false)
  const [error,           setError]           = useState<string | null>(null)

  // Load accounts once
  useEffect(() => {
    api.get<BetAccountDto[]>('/accounts')
      .then(r => setAccounts(r.data))
      .catch(() => setError('Не удалось загрузить счета'))
  }, [])

  // When accounts selection changes — reload persons (admin gets non-empty list)
  useEffect(() => {
    if (selectedAccIds.size === 0) { setPersons([]); setSelectedPerIds(new Set()); return }
    const ids = Array.from(selectedAccIds)
    api.get<BetPersonDto[]>('/persons', { params: ids, paramsSerializer: () =>
      ids.map(id => `accountIds=${id}`).join('&')
    }).then(r => {
      setPersons(r.data)
      // drop person selection if they're no longer in the list
      setSelectedPerIds(prev => {
        const valid = new Set(r.data.map(p => p.id))
        return new Set([...prev].filter(id => valid.has(id)))
      })
    }).catch(() => setPersons([]))
  }, [selectedAccIds])

  const handleToggleAccount = (id: string) => {
    setSelectedAccIds(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      return next
    })
    setData(null)
  }

  const handleTogglePerson = (id: string) => {
    setSelectedPerIds(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      return next
    })
  }

  // Load analytics when selection or filters change
  useEffect(() => {
    if (selectedAccIds.size === 0) { setData(null); return }

    const { from, to } = resolveDates(dateRange, customFrom, customTo)
    if (dateRange === 'custom' && (!from || !to)) return

    setLoading(true)
    setError(null)

    const accIds  = Array.from(selectedAccIds)
    const perIds  = Array.from(selectedPerIds)

    const parts: string[] = [
      ...accIds.map(id => `accountIds=${id}`),
      ...perIds.map(id => `personIds=${id}`),
    ]
    if (from) parts.push(`from=${encodeURIComponent(from)}`)
    if (to)   parts.push(`to=${encodeURIComponent(to)}`)

    api.get<AnalyticsResponse>(`/analytics?${parts.join('&')}`)
      .then(r => setData(r.data))
      .catch(() => setError('Ошибка загрузки аналитики'))
      .finally(() => setLoading(false))
  }, [selectedAccIds, selectedPerIds, dateRange, customFrom, customTo])

  const summary = data?.summary

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', overflow: 'hidden' }}>
      <FilterBar
        accounts={accounts} persons={persons}
        selectedAccIds={selectedAccIds} selectedPerIds={selectedPerIds}
        dateRange={dateRange} customFrom={customFrom} customTo={customTo}
        onToggleAccount={handleToggleAccount} onTogglePerson={handleTogglePerson}
        onDateRangeChange={setDateRange}
        onCustomFromChange={setCustomFrom} onCustomToChange={setCustomTo}
      />

      {summary && (
        <div style={summaryStyle}>
          <Chip label="Ставок" value={String(summary.totalBets)} />
          <Chip label="P&L"    value={fmtPnl(summary.totalPnl)}
                color={summary.totalPnl >= 0 ? '#22c55e' : '#ef4444'} />
          <Chip label="ROI"    value={`${summary.roi >= 0 ? '+' : ''}${summary.roi.toFixed(1)}%`}
                color={summary.roi >= 0 ? '#22c55e' : '#ef4444'} />
          <Chip label="W/L"   value={`${summary.wonBets}/${summary.lostBets}`} />
        </div>
      )}

      <div style={tabBarStyle}>
        {TABS.map(t => (
          <button key={t} onClick={() => setActiveTab(t)} style={{
            ...tabBtnStyle,
            borderBottom: t === activeTab
              ? '2px solid var(--tg-theme-button-color, #3b82f6)'
              : '2px solid transparent',
            color: t === activeTab
              ? 'var(--tg-theme-button-color, #3b82f6)'
              : 'var(--tg-theme-hint-color, #888)',
          }}>
            {t}
          </button>
        ))}
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: '8px 16px 24px' }}>
        {selectedAccIds.size === 0 && !loading && (
          <div style={hintStyle}>Выберите один или несколько счетов</div>
        )}
        {loading && <div style={hintStyle}>Загрузка...</div>}
        {error   && <div style={{ ...hintStyle, color: '#ef4444' }}>{error}</div>}

        {data && !loading && (
          <>
            {activeTab === 'Баланс'        && <BalanceDynamicsChart data={data.balanceDynamics} />}
            {activeTab === 'P&L'           && <PlChart data={data.plPoints} />}
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
  if (range === 'week')   return { from: now.subtract(7, 'day').toISOString(),  to: now.toISOString() }
  if (range === 'month')  return { from: now.subtract(30, 'day').toISOString(), to: now.toISOString() }
  if (range === 'custom') return {
    from: customFrom ? dayjs(customFrom).startOf('day').toISOString() : '',
    to:   customTo   ? dayjs(customTo).endOf('day').toISOString()     : '',
  }
  return { from: undefined, to: undefined }
}

function fmtPnl(v: number) { return (v >= 0 ? '+' : '') + v.toFixed(0) }

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
