import { DestroyRef, effect, inject, Injectable, NgZone, signal, untracked } from '@angular/core';
import { AuthService } from './auth.service';
import { NotificationApi } from './endpoints';
import type { AppNotification } from './models';

export const NOTIFICATION_POLL_MS = 30_000;

/**
 * Notification state as signals.
 * - the unread count polls every 30 s while a user is signed in (timer is torn down on logout/destroy);
 * - `markRead` is the ONLY optimistic update in the app: it flips the item and decrements the badge immediately and
 *   rolls both back if the request fails, then re-syncs from the server.
 */
@Injectable({ providedIn: 'root' })
export class NotificationStore {
  private readonly api = inject(NotificationApi);
  private readonly auth = inject(AuthService);
  private readonly zone = inject(NgZone);

  readonly unread = signal(0);
  readonly items = signal<AppNotification[]>([]);
  readonly listLoading = signal(false);
  readonly listError = signal(false);
  private timer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    effect(() => {
      const user = this.auth.user();
      untracked(() => {
        this.stopPolling();
        this.unread.set(0);
        this.items.set([]);
        if (user) this.startPolling();
      });
    });
    inject(DestroyRef).onDestroy(() => this.stopPolling());
  }

  private startPolling(): void {
    this.refreshCount();
    // poll outside the Angular zone (a periodic timer must not hold the zone unstable); re-enter for the HTTP call
    this.timer = this.zone.runOutsideAngular(() => setInterval(() => this.zone.run(() => this.refreshCount()), NOTIFICATION_POLL_MS));
  }
  private stopPolling(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  refreshCount(): void {
    this.api.unreadCount().subscribe({ next: (r) => this.unread.set(r.count), error: () => { /* keep last known count */ } });
  }

  loadList(unreadOnly = false): void {
    this.listLoading.set(true);
    this.listError.set(false);
    this.api.list(unreadOnly).subscribe({
      next: (p) => { this.items.set(p.content); this.listLoading.set(false); },
      error: () => { this.listError.set(true); this.listLoading.set(false); },
    });
  }

  markRead(id: string): void {
    const prevItems = this.items();
    const prevCount = this.unread();
    const wasUnread = prevItems.some((n) => n.id === id && !n.read);
    this.items.set(prevItems.map((n) => (n.id === id ? { ...n, read: true } : n)));
    if (wasUnread) this.unread.set(Math.max(0, prevCount - 1));
    this.api.markRead(id).subscribe({
      next: () => this.refreshCount(),
      error: () => { this.items.set(prevItems); this.unread.set(prevCount); }, // rollback
    });
  }
}
