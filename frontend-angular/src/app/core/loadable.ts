import { DestroyRef, inject, signal } from '@angular/core';
import { Observable, Subscription } from 'rxjs';

/**
 * Minimal server-state holder (the Angular answer to a query hook): signals for data/loading/error,
 * latest-request-wins (an in-flight request is cancelled by the next `load()`), and the previous data stays
 * visible while reloading (like `keepPreviousData`). Must be created in an injection context.
 */
export class Loadable<T> {
  readonly data = signal<T | null>(null);
  readonly loading = signal(false);
  readonly error = signal<unknown>(null);
  private sub?: Subscription;

  constructor(
    private readonly source: () => Observable<T>,
    private readonly onData?: (d: T) => void,
    destroyRef: DestroyRef = inject(DestroyRef),
  ) {
    destroyRef.onDestroy(() => this.sub?.unsubscribe());
  }

  load(): void {
    this.sub?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    this.sub = this.source().subscribe({
      next: (d) => { this.data.set(d); this.loading.set(false); this.onData?.(d); },
      error: (e: unknown) => { this.error.set(e); this.loading.set(false); },
    });
  }

  set(d: T): void { this.data.set(d); }
}

export function loadable<T>(source: () => Observable<T>, onData?: (d: T) => void): Loadable<T> {
  return new Loadable(source, onData);
}
