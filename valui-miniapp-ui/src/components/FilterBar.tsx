import type { BetAccountDto, BetPersonDto, DateRange } from '../api/types'
import dayjs from 'dayjs'

interface Props {
  accounts:       BetAccountDto[]
  persons:        BetPersonDto[]       // non-empty only for admin after accounts selected
  selectedAccIds: Set<string>
  selectedPerIds: Set<string>
  dateRange:      DateRange
  customFrom:     string
  customTo:       string
  onToggleAccount:   (id: string) => void
  onTogglePerson:    (id: string) => void
  onDateRangeChange: (r: DateRange) => void
  onCustomFromChange:(v: string) => void
  onCustomToChange:  (v: string) => void
}

export default function FilterBar({
  accounts, persons, selectedAccIds, selectedPerIds, dateRange,
  customFrom, customTo,
  onToggleAccount, onTogglePerson, onDateRangeChange,
  onCustomFromChange, onCustomToChange,
}: Props) {
  const isAdmin = persons.length > 0

  return (
    <div style={containerStyle}>

      {/* Accounts */}
      <SectionLabel>Счета</SectionLabel>
      <div style={checkListStyle}>
        {accounts.length === 0 && <div style={emptyStyle}>Нет счетов со ставками</div>}
        {accounts.map(a => (
          <CheckItem key={a.id} id={a.id} label={a.name}
                     checked={selectedAccIds.has(a.id)} onToggle={onToggleAccount} />
        ))}
      </div>

      {/* Persons — only for admin, only when accounts are selected */}
      {isAdmin && (
        <>
          <SectionLabel>Участники</SectionLabel>
          <div style={checkListStyle}>
            {persons.map(p => (
              <CheckItem key={p.id} id={p.id} label={p.displayName}
                         checked={selectedPerIds.has(p.id)} onToggle={onTogglePerson} />
            ))}
          </div>
        </>
      )}

      {/* Date range */}
      <div style={rowStyle}>
        {(['week', 'month', 'all', 'custom'] as DateRange[]).map(r => (
          <ToggleBtn key={r} active={dateRange === r} onClick={() => onDateRangeChange(r)}>
            {RANGE_LABEL[r]}
          </ToggleBtn>
        ))}
      </div>

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

function SectionLabel({ children }: { children: React.ReactNode }) {
  return <div style={sectionLabelStyle}>{children}</div>
}

function CheckItem({ id, label, checked, onToggle }: {
  id: string; label: string; checked: boolean; onToggle: (id: string) => void
}) {
  return (
    <label style={checkItemStyle}>
      <input type="checkbox" checked={checked} onChange={() => onToggle(id)}
             style={{ marginRight: 8, accentColor: 'var(--tg-theme-button-color, #3b82f6)' }} />
      {label}
    </label>
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
const sectionLabelStyle: React.CSSProperties = {
  fontSize: 11, fontWeight: 600, textTransform: 'uppercase',
  color: 'var(--tg-theme-hint-color, #888)', letterSpacing: '0.05em',
}
const rowStyle: React.CSSProperties = { display: 'flex', gap: 6, flexWrap: 'wrap' }
const btnStyle: React.CSSProperties = {
  padding: '5px 12px', borderRadius: 8, fontSize: 13,
  cursor: 'pointer', transition: 'all 0.15s',
}
const checkListStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 2, maxHeight: 120, overflowY: 'auto',
}
const checkItemStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', padding: '4px 2px',
  fontSize: 14, color: 'var(--tg-theme-text-color, #000)', cursor: 'pointer',
}
const emptyStyle: React.CSSProperties = {
  fontSize: 13, color: 'var(--tg-theme-hint-color, #888)', padding: '2px 0',
}
const dateInputStyle: React.CSSProperties = {
  flex: 1, padding: '6px 8px', borderRadius: 8, fontSize: 13,
  border: '1px solid var(--tg-theme-hint-color, #ddd)',
  background: 'var(--tg-theme-bg-color, #fff)',
  color: 'var(--tg-theme-text-color, #000)',
}
