export interface BalancePoint {
  date: string        // ISO LocalDate: "2025-05-01"
  pnl: number
  cumulative: number
}

export interface BetPlPoint {
  betId: string
  date: string
  title: string
  stake: number
  pnl: number
  odds: number
  status: 'OPEN' | 'WON' | 'LOST' | 'RETURNED' | 'CANCELLED'
}

export interface BucketEntry {
  label: string
  count: number
}

export interface DistributionData {
  byOutcome: Record<string, number>
  byStake: BucketEntry[]
  byOdds: BucketEntry[]
}

export interface AnalyticsSummary {
  totalBets: number
  openBets: number
  wonBets: number
  lostBets: number
  returnedBets: number
  totalStaked: number
  totalPnl: number
  roi: number
}

export interface AnalyticsResponse {
  balanceDynamics: BalancePoint[]
  plPoints: BetPlPoint[]
  distribution: DistributionData
  summary: AnalyticsSummary
}

export interface BetAccountDto {
  id: string
  chatId: number
  name: string
}

export interface BetPersonDto {
  id: string
  chatId: number
  displayName: string
}

export type AnalyticsScope = 'ACCOUNT' | 'PERSON'

export type DateRange = 'week' | 'month' | 'all' | 'custom'
