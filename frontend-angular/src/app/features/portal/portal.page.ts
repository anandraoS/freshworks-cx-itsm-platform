import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { KbApi, TicketApi } from '../../core/endpoints';
import { describeError } from '../../core/errors';
import { formatDateTime } from '../../core/format';
import { loadable } from '../../core/loadable';
import { PriorityBadgeComponent, StatusBadgeComponent } from '../../shared/ui/badges';
import { CardComponent } from '../../shared/ui/card';
import { EmptyStateComponent, ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';

/** Customer portal landing: own tickets (server-filtered with mine=true) + public published articles. */
@Component({
  selector: 'app-portal-page',
  imports: [RouterLink, CardComponent, SkeletonComponent, ErrorNoticeComponent, EmptyStateComponent, PriorityBadgeComponent, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-head">
      <h1>Welcome, {{ auth.user()?.displayName }}</h1>
      <a class="btn btn-primary btn-md" routerLink="/tickets/new">Create a ticket</a>
    </div>
    <div class="dash-grid">
      <app-card title="My recent tickets">
        <a cardActions routerLink="/tickets">View all</a>
        @if (tickets.loading() && !tickets.data()) { <app-skeleton label="Loading your tickets" /> }
        @if (tickets.error(); as e) { <app-error-notice [message]="describe(e)" /> }
        @if (tickets.data(); as p) {
          @if (p.content.length === 0) { <app-empty-state title="You have no tickets" description="Need help? Create a ticket and we will get back to you." /> }
          <ul class="plain-list">
            @for (tk of p.content; track tk.id) {
              <li>
                <a [routerLink]="['/tickets', tk.id]">#{{ tk.number }} {{ tk.title }}</a>
                <app-status-badge [status]="tk.status" /> <app-priority-badge [priority]="tk.priority" />
                <span class="muted small">{{ fmt(tk.updatedAt) }}</span>
              </li>
            }
          </ul>
        }
      </app-card>
      <app-card title="Help articles">
        <a cardActions routerLink="/kb">Browse all</a>
        @if (articles.loading() && !articles.data()) { <app-skeleton label="Loading articles" /> }
        @if (articles.error(); as e) { <app-error-notice [message]="describe(e)" /> }
        @if (articles.data(); as p) {
          @if (p.content.length === 0) { <app-empty-state title="No articles yet" /> }
          <ul class="plain-list">
            @for (a of p.content; track a.id) {
              <li><a [routerLink]="['/kb', a.id]">{{ a.title }}</a><br /><span class="muted small">{{ a.summary }}</span></li>
            }
          </ul>
        }
      </app-card>
    </div>
  `,
})
export class PortalPage implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly ticketApi = inject(TicketApi);
  private readonly kbApi = inject(KbApi);
  protected readonly tickets = loadable(() => this.ticketApi.list({ mine: true, size: 5, sort: 'createdAt,desc' }));
  protected readonly articles = loadable(() => this.kbApi.list({ size: 5, status: 'PUBLISHED', visibility: 'PUBLIC' }));
  protected readonly describe = describeError;
  protected readonly fmt = formatDateTime;

  ngOnInit(): void { this.tickets.load(); this.articles.load(); }
}
