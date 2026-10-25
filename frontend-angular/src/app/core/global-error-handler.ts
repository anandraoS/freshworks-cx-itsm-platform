import { ErrorHandler, inject, Injectable, signal } from '@angular/core';
import { ApiError } from './errors';

/**
 * Global ErrorHandler: last line of defence for unexpected errors (template/runtime bugs, unhandled promise rejections).
 * It logs a compact, secret-free line (never request bodies/headers) and exposes the last error as a signal that the
 * shell renders as an accessible banner. ApiErrors are expected and handled by their callers, so they are only logged.
 */
@Injectable({ providedIn: 'root' })
export class ErrorReporter {
  readonly lastError = signal<string | null>(null);
  report(e: unknown): void {
    const msg = e instanceof ApiError
      ? `API ${e.status} ${e.code} corr=${e.correlationId ?? '-'}`
      : e instanceof Error ? `${e.name}: ${e.message}` : String(e);
    console.error('[cx] unhandled error:', msg);
    if (!(e instanceof ApiError)) this.lastError.set('Something went wrong. Please reload the page and try again.');
  }
  dismiss(): void { this.lastError.set(null); }
}

@Injectable()
export class GlobalErrorHandler implements ErrorHandler {
  private readonly reporter = inject(ErrorReporter);
  handleError(error: unknown): void {
    this.reporter.report(error);
  }
}
