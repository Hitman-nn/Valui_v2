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
  tokenMonthlyGrantRef: number;
  controllersCount: number;
  createdAt: string;
}

export interface UserDetail {
  id: string;
  telegramId: number;
  username: string | null;
  firstName: string | null;
  languageCode: string;
  role: 'USER' | 'ADMIN';
  status: 'ACTIVE' | 'BANNED' | 'PENDING';
  tokenBalance: number;
  tokenMonthlyGrantRef: number;
  tokenLowThreshold: number | null;
  tokenStatsResetAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TokenTransactionDetail {
  id: string;
  delta: number;
  balanceAfter: number;
  refId: string | null;
  createdAt: string;
}

export interface TokenHistoryEntry {
  reasonCode: string;
  date: string;
  totalDelta: number;
  count: number;
  transactions: TokenTransactionDetail[];
}

export interface TokenStats {
  controllersUsed: number;
  tokenBalance: number;
  monthlyTokenGrant: number;
  tokenLowThreshold: number | null;
  recentHistory: TokenHistoryEntry[];
  spentThisMonth: number;
  avgPerMonth: number;
  spentAllTime: number;
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

export interface PollHistoryEntry {
  startedAt: string;
  durationMs: number;
  eventsFound: number;  // -1 = error
  status: 'ok' | 'error';
}

export interface UpdateControllerRequest {
  title?: string | null;
  filterRule?: string | null;
  pollIntervalSec?: number | null;
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
  maxMemoryBytes: number;
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
  status: 'ALL' | 'ACTIVE' | 'BANNED' | null;
}

export interface BroadcastResult {
  recipientCount: number;
  statusFilter: string | null;
}

// ─── Dashboard ────────────────────────────────────────────────────────────────

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

export interface ResendResult {
  eventId: string;
  controllerId: string;
  eventExternalId: string;
  notifyDedupCleared: number;
}

// ─── Scheduler ───────────────────────────────────────────────────────────────

export interface SchedulerConfig {
  maxConcurrentTasks: number;
  defaultPollIntervalSec: number;
  fetchBudgetMs: number;
  deferBaseMs: number;
  deferJitterMs: number;
  defaultUserWeight: number;
}

export interface SchedulerConfigUpdateRequest {
  maxConcurrentTasks?: number;
  defaultPollIntervalSec?: number;
  fetchBudgetMs?: number;
  deferBaseMs?: number;
  deferJitterMs?: number;
  defaultUserWeight?: number;
}

export interface SchedulerStats {
  scheduledJobs: number;
  queueDepth: number;
  availableSlots: number;
  maxSlots: number;
  starvationSec: number;
  tasksDeferred: number;
  tasksSkipped: number;
  eventsDetected: number;
  lagP50Ms: number;
  lagP95Ms: number;
  lagP99Ms: number;
  taskDurP50Ms: number;
  taskDurP95Ms: number;
  taskDurP99Ms: number;
}

export interface ControllerJobDto {
  controllerId: string;
  userId: string;
  pollIntervalSec: number;
  nextRunAt: string;
  lastStartedAt: string | null;
  lastFinishedAt: string | null;
  inFlight: boolean;
  overdueSec: number;
  status: 'IDLE' | 'IN_FLIGHT' | 'LATE';
  bookmaker: string | null;
  controllerTitle: string | null;
  username: string | null;
}

export interface ControllerJobDetail {
  job: ControllerJobDto;
  pollHistory: PollHistoryEntry[];
}

export interface SchedulerOverview {
  config: SchedulerConfig;
  stats: SchedulerStats;
  jobs: ControllerJobDto[];
}

export interface SchedulerMetricsSnapshot {
  ts: number;           // epoch millis
  queueDepth: number;
  inFlight: number;
  availableSlots: number;
  lagP95Ms: number;
  taskDurP95Ms: number;
  deferredTotal: number;
  eventsTotal: number;
}

export interface PollHistoryHourlyDto {
  hour: string;         // ISO instant
  totalPolls: number;
  avgDurationMs: number;
  okCount: number;
  errorCount: number;
  totalEvents: number;
}

// ─── Migration ───────────────────────────────────────────────────────────────

export interface LegacyControllerPreview {
  link: string;
  originalTitle: string | null;
  cleanTitle: string | null;
  bookmaker: string | null;
  controllerType: string;
  eventCount: number;
  ruleFilter: string | null;
}

export interface ChatGroup {
  chatId: string;
  controllerCount: number;
  controllers: LegacyControllerPreview[];
}

export interface ParsedMigration {
  groups: ChatGroup[];
  totalControllers: number;
  unknownBookmakerCount: number;
}

export interface ChatMapping {
  chatId: string;
  userId: string;
  notificationChatId: number;
}

export interface MigrationControllerEntry {
  chatId: string;
  link: string;
  title: string;
  ruleFilter: string | null;
  eventIds: string[];
}

export interface MigrationRequest {
  controllers: MigrationControllerEntry[];
  chatMappings: ChatMapping[];
  pollIntervalSec: number;
}

export interface DryRunResult {
  toImport: number;
  toSkip: number;
  toFail: number;
  byBookmaker: Record<string, number>;
}

export interface MigrationResult {
  imported: number;
  skipped: number;
  failed: number;
  dedupSeeded: number;
  byBookmaker: Record<string, number>;
}

// ─── Payments ─────────────────────────────────────────────────────────────────

export interface Payment {
  id: string;
  paymentId: string;
  amount: number;
  currency: string;
  status: string;
  description: string | null;
  createdAt: string;
}

// ─── Jobs ────────────────────────────────────────────────────────────────────

export interface SystemTaskDto {
  key: string;
  displayName: string;
  description: string;
  module: string;
  scheduleDescription: string;
  nextFireTime: string | null;
  lastRunAt: string | null;
  lastDurationMs: number | null;
  lastStatus: 'OK' | 'ERROR' | 'NEVER_RUN';
  lastErrorMessage: string | null;
  runCount: number;
  errorCount: number;
}

export interface PreMatchStats {
  pendingSnapshots: number;
  activeRetries: number;
  fetchSemaphoreAvailable: number;
  retryAttempts: Record<string, number>;
}

export interface JobsOverview {
  tasks: SystemTaskDto[];
  preMatch: PreMatchStats;
}

export interface TokenActionCost {
  actionCode:  string;
  costTokens:  number;
  description: string | null;
}

export interface UpdateTokenActionCostRequest {
  costTokens:  number;
  description?: string;
}

// ─── Crypto Exchange Rates ───────────────────────────────────────────────────

export interface ExchangeRate {
  currency: string;
  tokensPerUnit: number;
  updatedAt: string;
}

export interface UpdateExchangeRateRequest {
  tokensPerUnit: number;
}

export interface DlqStats {
  dlqFinalCount: number;
  replayed: number;
  status: string;
}
