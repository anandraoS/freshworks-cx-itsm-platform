import { Injectable, signal } from '@angular/core';

export type ToastKind = 'success' | 'error' | 'info';
export interface ToastItem { id: number; kind: ToastKind; message: string }

@Injectable({ providedIn: 'root' })
export class ToastService {
  private seq = 0;
  readonly toasts = signal<ToastItem[]>([]);

  push(kind: ToastKind, message: string, ttlMs = 5000): void {
    const id = ++this.seq;
    this.toasts.update((l) => [...l, { id, kind, message }]);
    if (ttlMs > 0) setTimeout(() => this.dismiss(id), ttlMs);
  }
  success(m: string) { this.push('success', m); }
  error(m: string) { this.push('error', m, 8000); }
  dismiss(id: number): void { this.toasts.update((l) => l.filter((t) => t.id !== id)); }
}
