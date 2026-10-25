import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { formatDateTime } from '../../core/format';
import { NotificationStore } from '../../core/notification.store';
import { BadgeComponent } from '../../shared/ui/badges';
import { ButtonComponent } from '../../shared/ui/button';
import { CardComponent } from '../../shared/ui/card';
import { EmptyStateComponent, ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';

@Component({
  selector: 'app-notifications-page',
  imports: [RouterLink, CardComponent, SkeletonComponent, ErrorNoticeComponent, EmptyStateComponent, BadgeComponent, ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-head">
      <h1>Notifications</h1>
      <label class="inline-check"><input type="checkbox" [checked]="unreadOnly()" (change)="toggle($any($event.target).checked)" /> Unread only</label>
    </div>
    <app-card>
      @if (store.listLoading() && store.items().length === 0) { <app-skeleton [lines]="5" label="Loading notifications" /> }
      @if (store.listError()) { <app-error-notice message="Could not load notifications." retryable (retry)="reload()" /> }
      @if (!store.listLoading() && !store.listError() && store.items().length === 0) {
        <app-empty-state title="No notifications" description="You are all caught up." />
      }
      <ul class="notif-list">
        @for (n of store.items(); track n.id) {
          <li [class]="n.read ? 'read' : 'unread'">
            <div>
              <strong>{{ n.title }}</strong> @if (!n.read) { <app-badge tone="info">New</app-badge> }
              <p>{{ n.body }}</p>
              <small class="muted">{{ fmt(n.createdAt) }}</small>
              @if (n.ticketId) { · <a [routerLink]="['/tickets', n.ticketId]">Open ticket</a> }
            </div>
            @if (!n.read) { <button appButton size="sm" variant="secondary" (click)="store.markRead(n.id)">Mark as read</button> }
          </li>
        }
      </ul>
    </app-card>
  `,
})
export class NotificationsPage implements OnInit {
  protected readonly store = inject(NotificationStore);
  protected readonly unreadOnly = signal(false);
  protected readonly fmt = formatDateTime;

  ngOnInit(): void { this.reload(); }
  protected reload(): void { this.store.loadList(this.unreadOnly()); }
  protected toggle(v: boolean): void { this.unreadOnly.set(v); this.reload(); }
}
