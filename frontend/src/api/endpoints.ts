import { api, request } from './client';
import type {
  ActivityEntry, AiSuggestion, AppNotification, Article, ArticleInput, AuditEvent, Category, DashboardData,
  LoginResponse, ManagedUser, Page, Role, SlaPolicy, Ticket, TicketComment, TicketInput, TicketStatus,
  TenantSettings, AuthUser, Workflow, WorkflowInput, Priority,
} from './types';

export interface TicketFilters {
  page?: number; size?: number; sort?: string; q?: string; status?: string; priority?: string;
  assigneeId?: string; categoryId?: string; type?: string; slaStatus?: string; mine?: boolean;
}

export const authApi = {
  login: (b: { tenantSlug: string; email: string; password: string }) =>
    request<LoginResponse>('/auth/login', { method: 'POST', body: b, skipAuthHandling: true }),
  me: () => api.get<AuthUser>('/auth/me'),
  logout: () => api.post<void>('/auth/logout'),
};

export const ticketApi = {
  list: (f: TicketFilters) => api.get<Page<Ticket>>('/tickets', { ...f }),
  get: (id: string) => api.get<Ticket>(`/tickets/${id}`),
  create: (body: TicketInput, idempotencyKey: string) =>
    api.post<Ticket>('/tickets', body, { 'Idempotency-Key': idempotencyKey }),
  update: (id: string, body: Pick<TicketInput, 'title' | 'description' | 'priority' | 'severity' | 'categoryId' | 'tags'> & { version: number }) =>
    api.put<Ticket>(`/tickets/${id}`, body),
  assign: (id: string, assigneeId: string | null, version: number) =>
    api.post<Ticket>(`/tickets/${id}/assign`, { assigneeId, version }),
  transition: (id: string, toStatus: TicketStatus, reason: string, version: number) =>
    api.post<Ticket>(`/tickets/${id}/transition`, { toStatus, reason, version }),
  comments: (id: string) => api.get<TicketComment[]>(`/tickets/${id}/comments`),
  addComment: (id: string, body: string, visibility: 'PUBLIC' | 'INTERNAL') =>
    api.post<TicketComment>(`/tickets/${id}/comments`, { body, visibility }),
  activity: (id: string) => api.get<ActivityEntry[]>(`/tickets/${id}/activity`),
};

export const categoryApi = {
  list: () => api.get<Category[]>('/ticket-categories'),
  create: (b: Omit<Category, 'id'>) => api.post<Category>('/ticket-categories', b),
  update: (id: string, b: Omit<Category, 'id'>) => api.put<Category>(`/ticket-categories/${id}`, b),
  remove: (id: string) => api.del<void>(`/ticket-categories/${id}`),
};

export const aiApi = {
  analyze: (ticketId: string) => api.post<AiSuggestion>(`/tickets/${ticketId}/ai/analyze`),
  suggestions: (ticketId: string) => api.get<AiSuggestion[]>(`/tickets/${ticketId}/ai/suggestions`),
  approve: (id: string, editedReply: string | undefined) =>
    api.post<AiSuggestion>(`/ai/suggestions/${id}/approve`, { editedReply, visibility: 'PUBLIC' }),
  reject: (id: string, reason: string) => api.post<AiSuggestion>(`/ai/suggestions/${id}/reject`, { reason }),
};

export interface ArticleFilters { page?: number; size?: number; q?: string; categoryId?: string; status?: string; visibility?: string }
export const kbApi = {
  list: (f: ArticleFilters) => api.get<Page<Article>>('/articles', { ...f }),
  get: (id: string) => api.get<Article>(`/articles/${id}`),
  create: (b: ArticleInput) => api.post<Article>('/articles', b),
  update: (id: string, b: ArticleInput) => api.put<Article>(`/articles/${id}`, b),
  publish: (id: string) => api.post<Article>(`/articles/${id}/publish`),
  versions: (id: string) => api.get<Article[]>(`/articles/${id}/versions`),
  categories: () => api.get<{ id: string; name: string }[]>('/article-categories'),
  createCategory: (name: string) => api.post<{ id: string; name: string }>('/article-categories', { name }),
};

export const analyticsApi = {
  dashboard: (days: number) => api.get<DashboardData>('/analytics/dashboard', { days }),
};

export const notificationApi = {
  list: (unread: boolean, page = 0, size = 20) => api.get<Page<AppNotification>>('/notifications', { unread: unread || undefined, page, size }),
  unreadCount: () => api.get<{ count: number }>('/notifications/unread-count'),
  markRead: (id: string) => api.post<void>(`/notifications/${id}/read`),
};

export const userApi = {
  list: () => api.get<ManagedUser[] | Page<ManagedUser>>('/users').then((r) => (Array.isArray(r) ? r : r.content)),
  create: (b: { email: string; displayName: string; roles: Role[]; password: string }) => api.post<ManagedUser>('/users', b),
  update: (id: string, b: { displayName: string; roles: Role[]; active: boolean }) => api.put<ManagedUser>(`/users/${id}`, b),
};

export const settingsApi = {
  get: () => api.get<TenantSettings>('/tenant/settings'),
  update: (b: TenantSettings) => api.put<TenantSettings>('/tenant/settings', b),
};

export const auditApi = {
  list: (f: { page?: number; size?: number; entityType?: string; actorId?: string }) => api.get<Page<AuditEvent>>('/audit-events', { ...f }),
};

export const workflowApi = {
  list: () => api.get<Workflow[]>('/workflows'),
  get: (id: string) => api.get<Workflow>(`/workflows/${id}`),
  validate: (b: WorkflowInput) => api.post<{ valid: boolean; errors: string[] }>('/workflows/validate', b),
  create: (b: WorkflowInput) => api.post<Workflow>('/workflows', b),
  activate: (id: string) => api.post<Workflow>(`/workflows/${id}/activate`),
};

export const slaApi = {
  list: () => api.get<SlaPolicy[]>('/sla-policies'),
  update: (id: string, b: { priority: Priority; firstResponseMinutes: number; resolutionMinutes: number; escalationAfterPercent: number[] }) =>
    api.put<SlaPolicy>(`/sla-policies/${id}`, b),
};
