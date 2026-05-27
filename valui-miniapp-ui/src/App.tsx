import AnalyticsPage from './pages/AnalyticsPage'

function getTelegramUserId(): number {
  try {
    const tg = (window as unknown as { Telegram?: { WebApp?: { initDataUnsafe?: { user?: { id?: number } } } } }).Telegram
    return tg?.WebApp?.initDataUnsafe?.user?.id ?? 0
  } catch {
    return 0
  }
}

export default function App() {
  const userId = getTelegramUserId()

  if (!userId) {
    return (
      <div style={{ textAlign: 'center', padding: '60px 24px',
                    color: 'var(--tg-theme-hint-color, #888)' }}>
        Откройте приложение из бота Valui
      </div>
    )
  }

  return <AnalyticsPage />
}
