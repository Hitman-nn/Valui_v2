import AnalyticsPage from './pages/AnalyticsPage'

function getChatId(): number {
  const params = new URLSearchParams(window.location.search)
  const fromUrl = params.get('chatId')
  if (fromUrl) return Number(fromUrl)

  // Fallback: use Telegram user ID from WebApp (private chat = userId == chatId)
  try {
    const tg = (window as unknown as { Telegram?: { WebApp?: { initDataUnsafe?: { user?: { id?: number } } } } }).Telegram
    return tg?.WebApp?.initDataUnsafe?.user?.id ?? 0
  } catch {
    return 0
  }
}

export default function App() {
  const chatId = getChatId()

  if (!chatId) {
    return (
      <div style={{ textAlign: 'center', padding: '60px 24px',
                    color: 'var(--tg-theme-hint-color, #888)' }}>
        Откройте приложение из бота Valui
      </div>
    )
  }

  return <AnalyticsPage chatId={chatId} />
}
