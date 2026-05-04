// ─── Pagination wrappers ────────────────────────────────────────────────────

export interface HalPage<T> {
  _embedded: Record<string, T[]>;
  page: {
    size: number;
    totalElements: number;
    totalPages: number;
    number: number;
  };
}

export interface SpringPage<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ─── Auth ────────────────────────────────────────────────────────────────────

export interface LoginRequest {
  telegramId: number;
  adminPassword: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

export interface RefreshRequest {
  refreshToken: string;
}

export interface LogoutRequest {
  refreshToken: string;
}

// ─── Users ───────────────────────────────────────────────────────────────────

export interface UserSummary {
  id: string;
  telegramId: number;
  username: string | null;
  firstName: string | null;
  role: 'USER' | 'ADMIN';
  status: 'ACTIVE' | 'BANNED' | 'PENDING';
  tokenBalance: number;
  createdAt: string;
}

export interface UserDetail extends UserSummary {
  languageCode: string;
  tokenMonthlyGrantRef: number;
  tokenLowThresholdPct: number;
  planCode: string | null;
  planName: string | null;
  maxControllers: number;
  subscriptionExpiresAt: string | null;
  subscriptionStartedAt: string | null;
  updatedAt: string;
}

// ─── Controllers ─────────────────────────────────────────────────────────────

export interface Controller {
  id: string;
  bookmaker: string;
  url: string;
  title: string | null;
  filterRule: string | null;
  isMuted: boolean;
  isActive: boolean;
  lastCheckedAt: string | null;
  lastEventAt: string | null;
  detectedEventsCount: number;
  type: string;
  notificationChatId: number | null;
  ownerTelegramId: number | null;
}

// ─── Subscriptions ────────────────────────────────────────────────────────────

export interface Subscription {
  id: string;
  userId: string;
  telegramId: number;
  username: string | null;
  planCode: string;
  planName: string;
  status: string;
  startedAt: string;
  expiresAt: string | null;
  paymentRef: string | null;
}

export interface SubscriptionStats {
  totalActive: number;
  expiringIn24h: number;
  byPlan: Array<{ planCode: string; planName: string; activeCount: number }>;
}

export interface GrantPlanRequest {
  planCode: string;
}

// ─── Parsers ─────────────────────────────────────────────────────────────────

export interface BookmakerStatus {
  bookmaker: string;
  cbState: string;
  indicator: 'green' | 'yellow' | 'red';
  successRate: number;
  successfulCalls: number;
  failedCalls: number;
  notPermittedCalls: number;
}

export interface TestParseRequest {
  url: string;
}

export interface TestParseResult {
  success: boolean;
  eventCount: number;
  sample: string[];
  errorMessage: string | null;
  latencyMs: number;
}

// ─── Audit / Notifications ────────────────────────────────────────────────────

export interface AuditLog {
  id: string;
  action: string;
  entityType: string | null;
  entityId: string | null;
  details: string | null;
  ipAddress: string | null;
  createdAt: string;
}

export interface NotificationLog {
  id: string;
  channel: string;
  status: string;
  attempts: number;
  sentAt: string | null;
  chatId: number | null;
  createdAt: string;
}

// ─── System ───────────────────────────────────────────────────────────────────

export interface SystemOverview {
  totalUsers: number;
  activeUsers: number;
  activeControllers: number;
  eventsToday: number;
  notificationsToday: number;
}

export interface KafkaLag {
  groupId: string;
  lagByTopic: Record<string, number>;
  totalLag: number;
}

export interface RedisInfo {
  usedMemoryBytes: number;
  usedMemoryHuman: string;
  usedMemoryPeakBytes: number;
  totalKeys: number;
}

export interface DbPool {
  activeConnections: number;
  pendingConnections: number;
  idleConnections: number;
  totalConnections: number;
  maxPoolSize: number;
}

// ─── Broadcast ────────────────────────────────────────────────────────────────

export interface BroadcastRequest {
  text: string;
  planCode: 'ALL' | 'FREE' | 'PRO' | 'PREMIUM' | null;
}

export interface BroadcastResult {
  recipientCount: number;
  planFilter: string | null;
}
