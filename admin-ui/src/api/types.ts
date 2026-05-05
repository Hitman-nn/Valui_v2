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
  controllersCount: number;
  planCode: string | null;
  subscriptionExpiresAt: string | null;
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
  pollIntervalSec: number | null;
}

export interface UpdateControllerRequest {
  title?: string | null;
  filterRule?: string | null;
  pollIntervalSec?: number | null;
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
  userId: string | null;
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

// ─── Dashboard ────────────────────────────────────────────────────────────────

export interface PlanCount {
  planCode: string;
  planName: string;
  count: number;
}

export interface DashboardSummary {
  users: {
    total: number;
    active: number;
    banned: number;
    newToday: number;
    newThisWeek: number;
  };
  controllers: {
    total: number;
    active: number;
    muted: number;
    stale: number;
  };
  events: {
    today: number;
    thisWeek: number;
    thisMonth: number;
    thisYear: number;
    total: number;
  };
  notifications: {
    today: number;
    thisWeek: number;
    thisMonth: number;
    thisYear: number;
    total: number;
  };
  subscriptions: {
    byPlan: PlanCount[];
    expiringIn7d: number;
  };
}

export interface DashboardJvm {
  heapUsedBytes: number;
  heapMaxBytes: number;
  nonHeapUsedBytes: number;
  threadsLive: number;
  uptimeSeconds: number;
  cpuUsagePct: number;
  httpRps: number;
  errorRate5xxPct: number;
  diskFreeBytes: number;
  diskTotalBytes: number;
}

export interface ActivityPoint {
  date: string;
  events: number;
  notifications: number;
}

export interface DashboardFull {
  summary: DashboardSummary;
  parsers: BookmakerStatus[];
  redis: RedisInfo;
  kafka: KafkaLag[];
  db: DbPool;
  jvm: DashboardJvm;
  activity: ActivityPoint[];
}

// ─── JVM History ─────────────────────────────────────────────────────────────

export interface JvmDataPoint {
  ts: number;         // epoch millis
  heapMb: number;
  heapMaxMb: number;
  nonHeapMb: number;
  threads: number;
  cpu: number;        // 0–100
}

// ─── Events ───────────────────────────────────────────────────────────────────

export interface AdminEvent {
  id: string;
  controllerId: string | null;
  controllerTitle: string | null;
  bookmaker: string | null;
  eventExternalId: string;
  title: string;
  url: string | null;
  detectedAt: string;
  expiresAt: string | null;
}

export interface EventStats {
  totalEvents: number;
  expiredEvents: number;
  byBookmaker: Array<{ bookmaker: string; count: number }>;
}

// ─── Payments ─────────────────────────────────────────────────────────────────

export interface Payment {
  id: string;
  paymentId: string;
  planCode: string;
  amount: number;
  currency: string;
  status: string;
  description: string | null;
  createdAt: string;
}
