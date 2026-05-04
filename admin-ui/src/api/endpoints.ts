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

// ─── Users ───────────────────────────────────────────────────────────────────

export const usersApi = {
  list: (params: { page?: number; size?: number; sort?: string }) =>
    apiClient
      .get<HalPage<UserSummary>>('/api/v1/admin/users', { params })
      .then((r) => r.data),

  get: (id: string) =>
    apiClient.get<UserDetail>(`/api/v1/admin/users/${id}`).then((r) => r.data),

  ban: (id: string) =>
    apiClient.post<void>(`/api/v1/admin/users/${id}/ban`).then((r) => r.data),

  unban: (id: string) =>
    apiClient.delete<void>(`/api/v1/admin/users/${id}/ban`).then((r) => r.data),

  setRole: (id: string, role: 'ADMIN' | 'USER') =>
    apiClient
      .patch<void>(`/api/v1/admin/users/${id}/role`, { role })
      .then((r) => r.data),

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
  list: (params: { page?: number; size?: number }) =>
    apiClient
      .get<HalPage<Controller>>('/api/v1/admin/controllers', { params })
      .then((r) => r.data),

  get: (id: string) =>
    apiClient.get<Controller>(`/api/v1/admin/controllers/${id}`).then((r) => r.data),

  delete: (id: string) =>
    apiClient.delete<void>(`/api/v1/admin/controllers/${id}`).then((r) => r.data),
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
      .post<BroadcastResult>('/api/v1/admin/notifications/broadcast', data)
      .then((r) => r.data),
};
