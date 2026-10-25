import { HttpClient, HttpContext, HttpHeaders, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { API_PREFIX, APP_CONFIG } from './config';
import { SKIP_AUTH_HANDLING } from './http-context';
import type {
  ActivityEntry, AiSuggestion, AppNotification, Article, ArticleInput, AuditEvent, AuthUser, Category, DashboardData,
  LoginResponse, ManagedUser, Page, Priority, Role, SlaPolicy, TenantSettings, Ticket, TicketComment, TicketInput,
  TicketStatus, Workflow, WorkflowInput,
} from './models';

export type QueryParams = Record<string, string | number | boolean | null | undefined>;

export interface TicketFilters {
  page?: number; size?: number; sort?: string; q?: string; status?: string; priority?: string;
  assigneeId?: string; categoryId?: string; type?: string; slaStatus?: string; mine?: boolean;
}
export interface ArticleFilters { page?: number; size?: number; q?: string; categoryId?: string; status?: string; visibility?: string }

export function buildParams(params?: QueryParams): HttpParams {
  let p = new HttpParams();
  if (!params) return p;
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    p = p.set(k, String(v));
  }
  return p;
}

/** Thin typed wrapper: base URL + `/api/v1` prefix + query-param cleaning. Auth/correlation/error mapping live in interceptors. */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);
  private readonly base = inject(APP_CONFIG).apiBaseUrl + API_PREFIX;

  get<T>(path: string, params?: QueryParams): Observable<T> {
    return this.http.get<T>(this.base + path, { params: buildParams(params) });
  }
  post<T>(path: string, body: unknown = {}, headers?: Record<string, string>, skipAuthHandling = false): Observable<T> {
    return this.http.post<T>(this.base + path, body, {
      headers: headers ? new HttpHeaders(headers) : undefined,
      context: skipAuthHandling ? new HttpContext().set(SKIP_AUTH_HANDLING, true) : undefined,
    });
  }
  put<T>(path: string, body: unknown = {}): Observable<T> { return this.http.put<T>(this.base + path, body); }
  delete<T>(path: string): Observable<T> { return this.http.delete<T>(this.base + path); }
}

@Injectable({ providedIn: 'root' })
export class AuthApi {
  private readonly api = inject(ApiClient);
  login(b: { tenantSlug: string; email: string; password: string }) { return this.api.post<LoginResponse>('/auth/login', b, undefined, true); }
  me() { return this.api.get<AuthUser>('/auth/me'); }
  logout() { return this.api.post<void>('/auth/logout'); }
}

@Injectable({ providedIn: 'root' })
export class TicketApi {
  private readonly api = inject(ApiClient);
  list(f: TicketFilters) { return this.api.get<Page<Ticket>>('/tickets', { ...f }); }
  get(id: string) { return this.api.get<Ticket>(`/tickets/${id}`); }
  create(body: TicketInput, idempotencyKey: string) { return this.api.post<Ticket>('/tickets', body, { 'Idempotency-Key': idempotencyKey }); }
  update(id: string, body: Pick<TicketInput, 'title' | 'description' | 'priority' | 'severity' | 'categoryId' | 'tags'> & { version: number }) {
    return this.api.put<Ticket>(`/tickets/${id}`, body);
  }
  assign(id: string, assigneeId: string | null, version: number) { return this.api.post<Ticket>(`/tickets/${id}/assign`, { assigneeId, version }); }
  transition(id: string, toStatus: TicketStatus, reason: string, version: number) {
    return this.api.post<Ticket>(`/tickets/${id}/transition`, { toStatus, reason, version });
  }
  comments(id: string) { return this.api.get<TicketComment[]>(`/tickets/${id}/comments`); }
  addComment(id: string, body: string, visibility: 'PUBLIC' | 'INTERNAL') { return this.api.post<TicketComment>(`/tickets/${id}/comments`, { body, visibility }); }
  activity(id: string) { return this.api.get<ActivityEntry[]>(`/tickets/${id}/activity`); }
}

@Injectable({ providedIn: 'root' })
export class CategoryApi {
  private readonly api = inject(ApiClient);
  list() { return this.api.get<Category[]>('/ticket-categories'); }
  create(b: Omit<Category, 'id'>) { return this.api.post<Category>('/ticket-categories', b); }
  update(id: string, b: Omit<Category, 'id'>) { return this.api.put<Category>(`/ticket-categories/${id}`, b); }
  remove(id: string) { return this.api.delete<void>(`/ticket-categories/${id}`); }
}

@Injectable({ providedIn: 'root' })
export class AiApi {
  private readonly api = inject(ApiClient);
  analyze(ticketId: string) { return this.api.post<AiSuggestion>(`/tickets/${ticketId}/ai/analyze`); }
  suggestions(ticketId: string) { return this.api.get<AiSuggestion[]>(`/tickets/${ticketId}/ai/suggestions`); }
  approve(id: string, editedReply: string | undefined) { return this.api.post<AiSuggestion>(`/ai/suggestions/${id}/approve`, { editedReply, visibility: 'PUBLIC' }); }
  reject(id: string, reason: string) { return this.api.post<AiSuggestion>(`/ai/suggestions/${id}/reject`, { reason }); }
}

@Injectable({ providedIn: 'root' })
export class KbApi {
  private readonly api = inject(ApiClient);
  list(f: ArticleFilters) { return this.api.get<Page<Article>>('/articles', { ...f }); }
  get(id: string) { return this.api.get<Article>(`/articles/${id}`); }
  create(b: ArticleInput) { return this.api.post<Article>('/articles', b); }
  update(id: string, b: ArticleInput) { return this.api.put<Article>(`/articles/${id}`, b); }
  publish(id: string) { return this.api.post<Article>(`/articles/${id}/publish`); }
  versions(id: string) { return this.api.get<Article[]>(`/articles/${id}/versions`); }
  categories() { return this.api.get<{ id: string; name: string }[]>('/article-categories'); }
  createCategory(name: string) { return this.api.post<{ id: string; name: string }>('/article-categories', { name }); }
}

@Injectable({ providedIn: 'root' })
export class AnalyticsApi {
  private readonly api = inject(ApiClient);
  dashboard(days: number) { return this.api.get<DashboardData>('/analytics/dashboard', { days }); }
}

@Injectable({ providedIn: 'root' })
export class NotificationApi {
  private readonly api = inject(ApiClient);
  list(unread: boolean, page = 0, size = 20) { return this.api.get<Page<AppNotification>>('/notifications', { unread: unread || undefined, page, size }); }
  unreadCount() { return this.api.get<{ count: number }>('/notifications/unread-count'); }
  markRead(id: string) { return this.api.post<void>(`/notifications/${id}/read`); }
}

@Injectable({ providedIn: 'root' })
export class UserApi {
  private readonly api = inject(ApiClient);
  list(): Observable<ManagedUser[]> {
    return this.api.get<ManagedUser[] | Page<ManagedUser>>('/users').pipe(map((r) => (Array.isArray(r) ? r : r.content)));
  }
  create(b: { email: string; displayName: string; roles: Role[]; password: string }) { return this.api.post<ManagedUser>('/users', b); }
  update(id: string, b: { displayName: string; roles: Role[]; active: boolean }) { return this.api.put<ManagedUser>(`/users/${id}`, b); }
}

@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly api = inject(ApiClient);
  get() { return this.api.get<TenantSettings>('/tenant/settings'); }
  update(b: TenantSettings) { return this.api.put<TenantSettings>('/tenant/settings', b); }
}

@Injectable({ providedIn: 'root' })
export class AuditApi {
  private readonly api = inject(ApiClient);
  list(f: { page?: number; size?: number; entityType?: string; actorId?: string }) { return this.api.get<Page<AuditEvent>>('/audit-events', { ...f }); }
}

@Injectable({ providedIn: 'root' })
export class WorkflowApi {
  private readonly api = inject(ApiClient);
  list() { return this.api.get<Workflow[]>('/workflows'); }
  get(id: string) { return this.api.get<Workflow>(`/workflows/${id}`); }
  validate(b: WorkflowInput) { return this.api.post<{ valid: boolean; errors: string[] }>('/workflows/validate', b); }
  create(b: WorkflowInput) { return this.api.post<Workflow>('/workflows', b); }
  activate(id: string) { return this.api.post<Workflow>(`/workflows/${id}/activate`); }
}

@Injectable({ providedIn: 'root' })
export class SlaApi {
  private readonly api = inject(ApiClient);
  list() { return this.api.get<SlaPolicy[]>('/sla-policies'); }
  update(id: string, b: { priority: Priority; firstResponseMinutes: number; resolutionMinutes: number; escalationAfterPercent: number[] }) {
    return this.api.put<SlaPolicy>(`/sla-policies/${id}`, b);
  }
}
