import apiClient from './client';
import type {
  AuthResponse,
  LoginRequest,
  RefreshRequest,
  LogoutRequest,
  HalPage,
  SpringPage,
  UserSummary,
  UserDetail,
  Controller,
  UpdateControllerRequest,
  Subscription,
  SubscriptionStats,
  GrantPlanRequest,
  BookmakerStatus,
  TestParseRequest,
  TestParseResult,
  AuditLog,
  NotificationLog,
  SystemOverview,
  KafkaLag,
  RedisInfo,
  DbPool,
  BroadcastRequest,
  BroadcastResult,
  DashboardFull,
  DashboardSummary,
  DashboardJvm,
  ActivityPoint,
  AdminEvent,
  EventStats,
  Payment,
} from './types';

// ─── Auth ────────────────────────────────────────────────────────────────────

export const authApi = {
  login: (data: LoginRequest) =>
    apiClient.post<AuthResponse>('/api/v1/auth/admin/login', data).then((r) => r.data),

  refresh: (data: RefreshRequest) =>
    apiClient.post<AuthResponse>('/api/v1/auth/refresh', data).then((r) => r.data),

  logout: (data: LogoutRequest) =>
    apiClient.post<void>('/api/v1/auth/logout', data).then((r) => r.data),
};

// ─── Dashboard ────────────────────────────────────────────────────────────────

export const dashboardApi = {
  full: (activityDays = 7) =>
    apiClient.get<DashboardFull>('/api/v1/admin/dashboard/full', {
      params: { activityDays },
    }).then((r) => r.data),

  summary: () =>
    apiClient.get<DashboardSummary>('/api/v1/admin/dashboard/summary').then((r) => r.data),

  jvm: () =>
    apiClient.get<DashboardJvm>('/api/v1/admin/dashboard/jvm').then((r) => r.data),

  activity: (days = 7) =>
    apiClient.get<ActivityPoint[]>('/api/v1/admin/dashboard/activity', {
      params: { days },
    }).then((r) => r.data),
};

// ─── Users ───────────────────────────────────────────────────────────────────

export const usersApi = {
  list: (params: {
    page?: number;
    size?: number;
    sort?: string;
    status?: string;
    role?: string;
    search?: string;
  }) =>
    apiClient
      .get<HalPage<UserSummary>>('/api/v1/admin/users', { params })
      .then((r) => r.data),

  get: (id: string) =>
    apiClient.get<UserDetail>(`/api/v1/admin/users/${id}`).then((r) => r.data),

  delete: (id: string) =>
    apiClient.delete<void>(`/api/v1/admin/users/${id}`).then((r) => r.data),

  ban: (id: string) =>
    apiClient.post<void>(`/api/v1/admin/users/${id}/ban`).then((r) => r.data),

  unban: (id: string) =>
    apiClient.delete<void>(`/api/v1/admin/users/${id}/ban`).then((r) => r.data),

  setRole: (id: string, role: 'ADMIN' | 'USER') =>
    apiClient
      .patch<void>(`/api/v1/admin/users/${id}/role`, { role })
      .then((r) => r.data),

  controllers: (id: string) =>
    apiClient.get<Controller[]>(`/api/v1/admin/users/${id}/controllers`).then((r) => r.data),

  subscription: (id: string) =>
    apiClient.get<Subscription>(`/api/v1/admin/users/${id}/subscription`).then((r) => r.data),

  grantPlan: (id: string, data: GrantPlanRequest) =>
    apiClient
      .post<void>(`/api/v1/admin/users/${id}/subscription`, data)
      .then((r) => r.data),

  payments: (id: string) =>
    apiClient.get<Payment[]>(`/api/v1/admin/users/${id}/payments`).then((r) => r.data),

  auditLog: (id: string, params: { page?: number; size?: number }) =>
    apiClient
      .get<SpringPage<AuditLog>>(`/api/v1/admin/users/${id}/audit-log`, { params })
      .then((r) => r.data),

  notifications: (id: string, params: { page?: number; size?: number }) =>
    apiClient
      .get<SpringPage<NotificationLog>>(`/api/v1/admin/users/${id}/notifications`, { params })
      .then((r) => r.data),
};

// ─── Controllers ─────────────────────────────────────────────────────────────

export const controllersApi = {
  list: (params: {
    page?: number;
    size?: number;
    bookmaker?: string;
    userId?: string;
    sort?: string;
  }) =>
    apiClient
      .get<HalPage<Controller>>('/api/v1/admin/controllers', { params })
      .then((r) => r.data),

  get: (id: string) =>
    apiClient.get<Controller>(`/api/v1/admin/controllers/${id}`).then((r) => r.data),

  update: (id: string, data: UpdateControllerRequest) =>
    apiClient
      .patch<Controller>(`/api/v1/admin/controllers/${id}`, data)
      .then((r) => r.data),

  toggle: (id: string) =>
    apiClient.patch<Controller>(`/api/v1/admin/controllers/${id}/toggle`).then((r) => r.data),

  setMute: (id: string, muted: boolean) =>
    apiClient
      .patch<Controller>(`/api/v1/admin/controllers/${id}/mute`, null, { params: { muted } })
      .then((r) => r.data),

  delete: (id: string) =>
    apiClient.delete<void>(`/api/v1/admin/controllers/${id}`).then((r) => r.data),

  events: (id: string, params: { page?: number; size?: number }) =>
    apiClient
      .get<SpringPage<AdminEvent>>(`/api/v1/admin/controllers/${id}/events`, { params })
      .then((r) => r.data),
};

// ─── Events ────────────────────────────────────────────────────────────────────

export const eventsApi = {
  list: (params: { page?: number; size?: number; controllerId?: string }) =>
    apiClient
      .get<SpringPage<AdminEvent>>('/api/v1/admin/events', { params })
      .then((r) => r.data),

  get: (id: string) =>
    apiClient.get<AdminEvent>(`/api/v1/admin/events/${id}`).then((r) => r.data),

  delete: (id: string) =>
    apiClient.delete<void>(`/api/v1/admin/events/${id}`).then((r) => r.data),

  deleteExpired: () =>
    apiClient.delete<number>('/api/v1/admin/events/expired').then((r) => r.data),

  stats: () =>
    apiClient.get<EventStats>('/api/v1/admin/events/stats').then((r) => r.data),
};

// ─── Subscriptions ────────────────────────────────────────────────────────────

export const subscriptionsApi = {
  list: (params: { page?: number; size?: number }) =>
    apiClient
      .get<SpringPage<Subscription>>('/api/v1/admin/subscriptions', { params })
      .then((r) => r.data),

  expiring: () =>
    apiClient
      .get<Subscription[]>('/api/v1/admin/subscriptions/expiring')
      .then((r) => r.data),

  stats: () =>
    apiClient
      .get<SubscriptionStats>('/api/v1/admin/subscriptions/stats')
      .then((r) => r.data),

  grant: (userId: string, data: GrantPlanRequest) =>
    apiClient
      .post<void>(`/api/v1/admin/subscriptions/users/${userId}/grant`, data)
      .then((r) => r.data),
};

// ─── Parsers ─────────────────────────────────────────────────────────────────

export const parsersApi = {
  list: () =>
    apiClient
      .get<BookmakerStatus[]>('/api/v1/admin/parsers')
      .then((r) => r.data),

  test: (bookmaker: string, data: TestParseRequest) =>
    apiClient
      .post<TestParseResult>(`/api/v1/admin/parsers/${bookmaker}/test`, data)
      .then((r) => r.data),

  poll: (bookmaker: string) =>
    apiClient
      .post<TestParseResult>(`/api/v1/admin/parsers/${bookmaker}/poll`)
      .then((r) => r.data),

  pollAll: () =>
    apiClient
      .post<Record<string, TestParseResult>>('/api/v1/admin/parsers/poll-all')
      .then((r) => r.data),
};

// ─── Audit ────────────────────────────────────────────────────────────────────

export const auditApi = {
  list: (params: { page?: number; size?: number; action?: string }) =>
    apiClient
      .get<SpringPage<AuditLog>>('/api/v1/admin/audit', { params })
      .then((r) => r.data),

  byUser: (userId: string, params: { page?: number; size?: number }) =>
    apiClient
      .get<SpringPage<AuditLog>>(`/api/v1/admin/audit/user/${userId}`, { params })
      .then((r) => r.data),
};

// ─── System ───────────────────────────────────────────────────────────────────

export const systemApi = {
  overview: () =>
    apiClient.get<SystemOverview>('/api/v1/admin/system/overview').then((r) => r.data),

  kafka: () =>
    apiClient.get<KafkaLag[]>('/api/v1/admin/system/kafka').then((r) => r.data),

  redis: () =>
    apiClient.get<RedisInfo>('/api/v1/admin/system/redis').then((r) => r.data),

  db: () =>
    apiClient.get<DbPool>('/api/v1/admin/system/db').then((r) => r.data),
};

// ─── Broadcast ────────────────────────────────────────────────────────────────

export const broadcastApi = {
  send: (data: BroadcastRequest) =>
    apiClient
      .post<BroadcastResult>('/api/v1/admin/broadcast', data)
      .then((r) => r.data),

  preview: (planCode?: string) =>
    apiClient
      .get<BroadcastResult>('/api/v1/admin/broadcast/preview', {
        params: planCode ? { planCode } : {},
      })
      .then((r) => r.data),
};
