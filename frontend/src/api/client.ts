import { tokenStore } from '../auth/tokenStore';
import { newId } from '../lib/correlation';
import type { ApiErrorBody, ApiFieldError } from './types';

export const API_BASE: string = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? 'http://localhost:8080';
const PREFIX = '/api/v1';

export type ApiErrorKind =
  | 'validation' | 'unauthenticated' | 'forbidden' | 'not_found' | 'version_conflict' | 'duplicate'
  | 'invalid_transition' | 'rate_limited' | 'ai_unavailable' | 'server' | 'network' | 'unknown';

export function mapKind(status: number, code: string): ApiErrorKind {
  switch (code) {
    case 'VALIDATION_FAILED': return 'validation';
    case 'UNAUTHENTICATED': return 'unauthenticated';
    case 'FORBIDDEN': return 'forbidden';
    case 'NOT_FOUND': return 'not_found';
    case 'TICKET_VERSION_CONFLICT': return 'version_conflict';
    case 'DUPLICATE_SUBMISSION': return 'duplicate';
    case 'INVALID_TRANSITION': return 'invalid_transition';
    case 'RATE_LIMITED': return 'rate_limited';
    case 'AI_UNAVAILABLE': return 'ai_unavailable';
    case 'INTERNAL_ERROR': return 'server';
  }
  if (status === 0) return 'network';
  if (status === 400) return 'validation';
  if (status === 401) return 'unauthenticated';
  if (status === 403) return 'forbidden';
  if (status === 404) return 'not_found';
  if (status === 409) return 'version_conflict';
  if (status === 422) return 'invalid_transition';
  if (status === 429) return 'rate_limited';
  if (status === 503) return 'ai_unavailable';
  if (status >= 500) return 'server';
  return 'unknown';
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly kind: ApiErrorKind;
  readonly fieldErrors: ApiFieldError[];
  readonly correlationId?: string;
  constructor(status: number, code: string, message: string, fieldErrors: ApiFieldError[] = [], correlationId?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.kind = mapKind(status, code);
    this.fieldErrors = fieldErrors;
    this.correlationId = correlationId;
  }
}

/** Human-readable message that never leaks internals. */
export function describeError(e: unknown): string {
  if (!(e instanceof ApiError)) return 'Something went wrong. Please try again.';
  switch (e.kind) {
    case 'network': return 'Cannot reach the server. Check your connection and try again.';
    case 'forbidden': return 'You do not have permission to do that.';
    case 'not_found': return 'That item was not found.';
    case 'rate_limited': return 'Too many requests. Please wait a moment.';
    case 'server': return 'The server had a problem. Please try again.';
    default: return e.message || 'Request failed.';
  }
}

let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(fn: (() => void) | null): void { onUnauthorized = fn; }

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  params?: Record<string, string | number | boolean | null | undefined>;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /** Do not trigger global logout on 401 (used by the login call itself). */
  skipAuthHandling?: boolean;
}

export function buildQuery(params?: RequestOptions['params']): string {
  if (!params) return '';
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    sp.set(k, String(v));
  }
  const s = sp.toString();
  return s ? `?${s}` : '';
}

export async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-Correlation-Id': newId(),
    ...opts.headers,
  };
  const token = tokenStore.token();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';

  let res: Response;
  try {
    res = await fetch(`${API_BASE}${PREFIX}${path}${buildQuery(opts.params)}`, {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      signal: opts.signal,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e;
    throw new ApiError(0, 'NETWORK_ERROR', 'Network error');
  }

  if (res.ok) {
    if (res.status === 204) return undefined as T;
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  let body: Partial<ApiErrorBody> = {};
  try { body = (await res.json()) as Partial<ApiErrorBody>; } catch { /* non-JSON error body */ }
  const err = new ApiError(
    res.status,
    body.code ?? '',
    body.message ?? res.statusText,
    body.fieldErrors ?? [],
    body.correlationId ?? res.headers.get('X-Correlation-Id') ?? undefined,
  );
  if (err.kind === 'unauthenticated' && !opts.skipAuthHandling) onUnauthorized?.();
  throw err;
}

export const api = {
  get: <T>(p: string, params?: RequestOptions['params'], signal?: AbortSignal) => request<T>(p, { params, signal }),
  post: <T>(p: string, body?: unknown, headers?: Record<string, string>) => request<T>(p, { method: 'POST', body: body ?? {}, headers }),
  put: <T>(p: string, body?: unknown) => request<T>(p, { method: 'PUT', body: body ?? {} }),
  del: <T>(p: string) => request<T>(p, { method: 'DELETE' }),
};
