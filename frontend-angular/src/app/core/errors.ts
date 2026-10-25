import type { ApiFieldError } from './models';

export type ApiErrorKind =
  | 'validation' | 'unauthenticated' | 'forbidden' | 'not_found' | 'version_conflict' | 'duplicate'
  | 'invalid_transition' | 'rate_limited' | 'ai_unavailable' | 'server' | 'network' | 'unknown';

/** Maps the contract `code` (falling back to the HTTP status) to a UI-level error kind. */
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
    default: break;
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

/** Normalised API error (contract error schema). Thrown by the error interceptor for every non-2xx / network failure. */
export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly fieldErrors: ApiFieldError[] = [],
    readonly correlationId?: string,
  ) {
    super(message);
    this.name = 'ApiError';
    this.kind = mapKind(status, code);
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

export function isKind(e: unknown, kind: ApiErrorKind): e is ApiError {
  return e instanceof ApiError && e.kind === kind;
}
