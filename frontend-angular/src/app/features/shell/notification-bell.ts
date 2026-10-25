import { ChangeDetectionStrategy, Component, ElementRef, HostListener, inject, signal, viewChild } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { formatDateTime } from '../../core/format';
import type { AppNotification } from '../../core/models';
import { NotificationStore } from '../../core/notification.store';

@Component({
  selector: 'app-notification-bell',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bell">
      <button #btn type="button" class="bell-btn" aria-haspopup="true" [attr.aria-expanded]="open()"
              [attr.aria-label]="'Notifications, ' + store.unread() + ' unread'" (click)="toggle()">
        <span aria-hidden="true">🔔</span>
        @if (store.unread() > 0) { <span class="bell-count" data-testid="unread-count">{{ store.unread() > 99 ? '99+' : store.unread() }}</span> }
      </button>
      @if (open()) {
        <div class="bell-panel" role="region" aria-label="Recent notifications">
          @if (store.listLoading() && store.items().length === 0) { <p class="muted">Loading…</p> }
          @if (store.listError()) { <p class="muted">Could not load notifications.</p> }
          @if (!store.listLoading() && !store.listError() && store.items().length === 0) { <p class="muted">You are all caught up.</p> }
          <ul>
            @for (n of recent(); track n.id) {
              <li [class]="n.read ? 'read' : 'unread'">
                <button type="button" class="bell-item" (click)="select(n)">
                  <strong>{{ n.title }}</strong>
                  <span>{{ n.body }}</span>
                  <small>{{ fmt(n.createdAt) }}</small>
                </button>
              </li>
            }
          </ul>
          <a routerLink="/notifications" (click)="open.set(false)" class="bell-all">View all notifications</a>
        </div>
      }
    </div>
  `,
})
export class NotificationBellComponent {
  protected readonly store = inject(NotificationStore);
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly btn = viewChild.required<ElementRef<HTMLButtonElement>>('btn');
  protected readonly open = signal(false);
  protected readonly fmt = formatDateTime;

  protected recent(): AppNotification[] { return this.store.items().slice(0, 8); }

  protected toggle(): void {
    const next = !this.open();
    this.open.set(next);
    if (next) this.store.loadList(false);
  }

  protected select(n: AppNotification): void {
    if (!n.read) this.store.markRead(n.id);
    if (n.ticketId) { this.open.set(false); void this.router.navigate(['/tickets', n.ticketId]); }
  }

  @HostListener('document:mousedown', ['$event'])
  onDoc(e: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(e.target as Node)) this.open.set(false);
  }

  @HostListener('document:keydown', ['$event'])
  onKey(e: KeyboardEvent): void {
    if (this.open() && e.key === 'Escape') { this.open.set(false); this.btn().nativeElement.focus(); }
  }
}
