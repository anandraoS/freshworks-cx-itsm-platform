import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { APP_CONFIG } from './config';
import { newId } from './correlation';
import { ApiError } from './errors';
import type { ApiErrorBody } from './models';
import { TokenStore } from './token-store';
import { SKIP_AUTH_HANDLING } from './http-context';

const isApiCall = (url: string, base: string) => url.startsWith(base);

/** Adds `Authorization: Bearer`, a fresh `X-Correlation-Id` and `Accept` to every API request. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const cfg = inject(APP_CONFIG);
  if (!isApiCall(req.url, cfg.apiBaseUrl)) return next(req);
  const token = inject(TokenStore).token();
  let headers = req.headers.set('Accept', 'application/json');
  if (!headers.has('X-Correlation-Id')) headers = headers.set('X-Correlation-Id', newId());
  if (token) headers = headers.set('Authorization', `Bearer ${token}`);
  return next(req.clone({ headers }));
};

/** Converts every failure to the contract-shaped {@link ApiError}; a 401 ends the session. */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const cfg = inject(APP_CONFIG);
  const auth = inject(AuthService);
  return next(req).pipe(
    catchError((e: unknown) => {
      if (!(e instanceof HttpErrorResponse) || !isApiCall(req.url, cfg.apiBaseUrl)) return throwError(() => e);
      const err = toApiError(e);
      if (err.kind === 'unauthenticated' && !req.context.get(SKIP_AUTH_HANDLING)) auth.handleUnauthorized();
      return throwError(() => err);
    }),
  );
};

export function toApiError(e: HttpErrorResponse): ApiError {
  if (e.status === 0) return new ApiError(0, 'NETWORK_ERROR', 'Network error');
  let body: Partial<ApiErrorBody> = {};
  if (e.error && typeof e.error === 'object') body = e.error as Partial<ApiErrorBody>;
  else if (typeof e.error === 'string') {
    try { body = JSON.parse(e.error) as Partial<ApiErrorBody>; } catch { /* non-JSON error body */ }
  }
  return new ApiError(
    e.status,
    body.code ?? '',
    body.message ?? e.statusText,
    body.fieldErrors ?? [],
    body.correlationId ?? e.headers?.get('X-Correlation-Id') ?? undefined,
  );
}
