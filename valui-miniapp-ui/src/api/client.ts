import axios from 'axios'

function getInitData(): string {
  try {
    const tg = (window as unknown as { Telegram?: { WebApp?: { initData?: string } } }).Telegram
    return tg?.WebApp?.initData || ''
  } catch {
    return ''
  }
}

const api = axios.create({
  baseURL: '/api/miniapp',
})

api.interceptors.request.use((config) => {
  const initData = getInitData()
  if (initData) {
    config.headers['X-Telegram-Init-Data'] = initData
  }
  return config
})

export default api
