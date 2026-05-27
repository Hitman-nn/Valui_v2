import type { BetAccountDto, BetPersonDto, AnalyticsScope, DateRange } from '../api/types'
import dayjs from 'dayjs'

interface Props {
  accounts:     BetAccountDto[]
  persons:      BetPersonDto[]
  scope:        AnalyticsScope
  selectedId:   string
  dateRange:    DateRange
  customFrom:   string
  customTo:     string
  onScopeChange:      (s: AnalyticsScope) => void
  onSelectedIdChange: (id: string) => void
  onDateRangeChange:  (r: DateRange) => void
  onCustomFromChange: (v: string) => void
  onCustomToChange:   (v: string) => void
}

export default function FilterBar({
  accounts, persons, scope, selectedId, dateRange,
  customFrom, customTo,
  onScopeChange, onSelectedIdChange, onDateRangeChange,
  onCustomFromChange, onCustomToChange,
}: Props) {
  const items = scope === 'ACCOUNT'
    ? accounts.map(a => ({ id: a.id, label: a.name }))
    : persons.map(p => ({ id: p.id, label: p.displayName }))

  return (
    <div style={containerStyle}>
      {/* Scope toggle */}
      <div style={rowStyle}>
        <ToggleBtn active={scope === 'ACCOUNT'} onClick={() => { onScopeChange('ACCOUNT'); onSelectedIdChange('') }}>
          Счёт
        </ToggleBtn>
        <ToggleBtn active={scope === 'PERSON'} onClick={() => { onScopeChange('PERSON'); onSelectedIdChange('') }}>
          Участник
        </ToggleBtn>
      </div>

      {/* Entity selector */}
      <select style={selectStyle} value={selectedId} onChange={e => onSelectedIdChange(e.target.value)}>
        <option value="">— Выберите {scope === 'ACCOUNT' ? 'счёт' : 'участника'} —</option>
        {items.map(item => (
          <option key={item.id} value={item.id}>{item.label}</option>
        ))}
      </select>

      {/* Date range */}
      <div style={rowStyle}>
        {(['week', 'month', 'all', 'custom'] as DateRange[]).map(r => (
          <ToggleBtn key={r} active={dateRange === r} onClick={() => onDateRangeChange(r)}>
            {RANGE_LABEL[r]}
          </ToggleBtn>
        ))}
      </div>

      {/* Custom date inputs */}
      {dateRange === 'custom' && (
        <div style={rowStyle}>
          <input type="date" style={dateInputStyle} value={customFrom}
                 max={customTo || dayjs().format('YYYY-MM-DD')}
                 onChange={e => onCustomFromChange(e.target.value)} />
          <span style={{ color: 'var(--tg-theme-hint-color, #888)', fontSize: 13 }}>—</span>
          <input type="date" style={dateInputStyle} value={customTo}
                 min={customFrom} max={dayjs().format('YYYY-MM-DD')}
                 onChange={e => onCustomToChange(e.target.value)} />
        </div>
      )}
    </div>
  )
}

function ToggleBtn({ active, onClick, children }: {
  active: boolean; onClick: () => void; children: React.ReactNode
}) {
  return (
    <button onClick={onClick} style={{
      ...btnStyle,
      background: active ? 'var(--tg-theme-button-color, #3b82f6)' : 'transparent',
      color: active ? 'var(--tg-theme-button-text-color, #fff)' : 'var(--tg-theme-text-color, #000)',
      border: active ? 'none' : '1px solid var(--tg-theme-hint-color, #ccc)',
    }}>
      {children}
    </button>
  )
}

const RANGE_LABEL: Record<DateRange, string> = {
  week: '7 дн', month: '30 дн', all: 'Всё', custom: 'Период',
}

const containerStyle: React.CSSProperties = {
  padding: '12px 16px',
  background: 'var(--tg-theme-secondary-bg-color, #f8f8f8)',
  borderBottom: '1px solid var(--tg-theme-hint-color, #eee)',
  display: 'flex', flexDirection: 'column', gap: 8,
}
const rowStyle: React.CSSProperties = { display: 'flex', gap: 6, flexWrap: 'wrap' }
const btnStyle: React.CSSProperties = {
  padding: '5px 12px', borderRadius: 8, fontSize: 13,
  cursor: 'pointer', transition: 'all 0.15s',
}
const selectStyle: React.CSSProperties = {
  width: '100%', padding: '7px 10px', borderRadius: 8, fontSize: 13,
  border: '1px solid var(--tg-theme-hint-color, #ddd)',
  background: 'var(--tg-theme-bg-color, #fff)',
  color: 'var(--tg-theme-text-color, #000)',
}
const dateInputStyle: React.CSSProperties = {
  flex: 1, padding: '6px 8px', borderRadius: 8, fontSize: 13,
  border: '1px solid var(--tg-theme-hint-color, #ddd)',
  background: 'var(--tg-theme-bg-color, #fff)',
  color: 'var(--tg-theme-text-color, #000)',
}
