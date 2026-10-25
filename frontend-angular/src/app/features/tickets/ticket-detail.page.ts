import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { TicketApi } from '../../core/endpoints';
import { describeError, isKind } from '../../core/errors';
import { formatDateTime, humanize } from '../../core/format';
import { loadable } from '../../core/loadable';
import { BadgeComponent, PriorityBadgeComponent, StatusBadgeComponent } from '../../shared/ui/badges';
import { ButtonComponent } from '../../shared/ui/button';
import { CardComponent } from '../../shared/ui/card';
import { EmptyStateComponent, ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';
import { SlaIndicatorComponent } from '../../shared/ui/sla-indicator';
import { AiPanelComponent } from './ai-panel';
import { ActivityCardComponent, AssignCardComponent, CommentsCardComponent, TransitionCardComponent } from './ticket-parts';
import type { Ticket } from '../../core/models';

@Component({
  selector: 'app-ticket-detail-page',
  imports: [
    RouterLink, ButtonComponent, CardComponent, BadgeComponent, PriorityBadgeComponent, StatusBadgeComponent, SlaIndicatorComponent,
    SkeletonComponent, EmptyStateComponent, ErrorNoticeComponent, AiPanelComponent, AssignCardComponent, TransitionCardComponent,
    CommentsCardComponent, ActivityCardComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (q.loading() && !q.data()) { <app-skeleton [lines]="10" label="Loading ticket" /> }
    @if (q.error(); as e) {
      @if (notFound()) {
        <app-empty-state title="Ticket not found" description="It may not exist, or you may not have access to it.">
          <a routerLink="/tickets">Back to tickets</a>
        </app-empty-state>
      } @else { <app-error-notice [message]="describe(e)" retryable (retry)="q.load()" /> }
    }
    @if (q.data(); as tk) {
      <div class="page-head">
        <div>
          <p class="muted small"><a routerLink="/tickets">← Tickets</a></p>
          <h1>#{{ tk.number }} {{ tk.title }}</h1>
        </div>
        @if (auth.isStaff()) { <a class="btn btn-secondary btn-md" [routerLink]="['/tickets', tk.id, 'edit']">Edit</a> }
      </div>

      @if (conflict()) {
        <div class="notice notice-warn" role="alert" data-testid="conflict-banner">
          <span>This ticket was modified by someone else. Your change was not applied. Reload to see the latest version, then try again.</span>
          <button appButton size="sm" (click)="reloadLatest()">Reload latest</button>
        </div>
      }

      <div class="detail-grid">
        <div class="detail-main">
          <app-card title="Details">
            <div class="badges">
              <app-status-badge [status]="tk.status" /> <app-priority-badge [priority]="tk.priority" />
              <app-badge>{{ human(tk.type) }}</app-badge> <app-badge>Severity: {{ human(tk.severity) }}</app-badge>
              @if (tk.reopenCount > 0) { <app-badge tone="warn">Reopened {{ tk.reopenCount }}x</app-badge> }
              @if (tk.escalationLevel > 0) { <app-badge tone="danger">Escalation L{{ tk.escalationLevel }}</app-badge> }
            </div>
            <p class="description">{{ tk.description }}</p>
            <dl class="meta">
              <div><dt>Requester</dt><dd>{{ tk.requester.displayName }}</dd></div>
              <div><dt>Category</dt><dd>{{ tk.categoryName ?? '—' }}</dd></div>
              <div><dt>Created</dt><dd>{{ fmt(tk.createdAt) }}</dd></div>
              <div><dt>Updated</dt><dd>{{ fmt(tk.updatedAt) }}</dd></div>
              <div><dt>Tags</dt><dd>@for (x of tk.tags; track x) { <app-badge>{{ x }}</app-badge> } @empty { — }</dd></div>
              <div><dt>Attachments</dt><dd>@for (a of tk.attachments; track $index; let last = $last) { {{ a.fileName }}{{ last ? '' : ', ' }} } @empty { — }</dd></div>
            </dl>
          </app-card>
          <app-comments-card [ticketId]="tk.id" [refreshKey]="commentsTick()" (changed)="activityTick.update(bump)" />
          <app-activity-card [ticketId]="tk.id" [refreshKey]="activityTick()" />
        </div>

        <aside class="detail-side">
          <app-card title="SLA">
            <dl class="meta meta-col">
              <div><dt>Status</dt><dd><app-sla-indicator [status]="tk.slaStatus" /></dd></div>
              <div>
                <dt>First response due</dt>
                <dd>
                  {{ fmt(tk.firstResponseDueAt) }}
                  @if (!tk.firstRespondedAt && tk.firstResponseDueAt) { <app-sla-indicator [status]="tk.slaStatus" [due]="tk.firstResponseDueAt" /> }
                  @if (tk.firstRespondedAt) { <small class="muted"> (responded {{ fmt(tk.firstRespondedAt) }})</small> }
                </dd>
              </div>
              <div>
                <dt>Resolution due</dt>
                <dd>
                  {{ fmt(tk.resolutionDueAt) }}
                  @if (!tk.resolvedAt && tk.resolutionDueAt) { <app-sla-indicator [status]="tk.slaStatus" [due]="tk.resolutionDueAt" /> }
                  @if (tk.resolvedAt) { <small class="muted"> (resolved {{ fmt(tk.resolvedAt) }})</small> }
                </dd>
              </div>
            </dl>
          </app-card>
          @if (auth.isStaff()) {
            <app-assign-card [ticket]="tk" (updated)="onUpdated($event)" (conflict)="conflict.set(true)" />
            <app-transition-card [ticket]="tk" (updated)="onUpdated($event)" (conflict)="conflict.set(true)" />
            <!-- the AI panel is its own lazy chunk: only staff ever load it -->
            @defer (on immediate) { <app-ai-panel [ticketId]="tk.id" (decided)="commentsTick.update(bump); activityTick.update(bump)" /> } @placeholder { <app-skeleton [lines]="3" label="Loading AI assist" /> }
          }
        </aside>
      </div>
    }
  `,
})
export class TicketDetailPage {
  protected readonly auth = inject(AuthService);
  private readonly api = inject(TicketApi);
  readonly id = input.required<string>();

  protected readonly q = loadable(() => this.api.get(this.id()));
  protected readonly conflict = signal(false);
  protected readonly commentsTick = signal(0);
  protected readonly activityTick = signal(0);
  protected readonly bump = (n: number) => n + 1;
  protected readonly human = humanize;
  protected readonly fmt = formatDateTime;
  protected readonly describe = describeError;

  constructor() {
    effect(() => { this.id(); untracked(() => { this.conflict.set(false); this.q.load(); }); });
  }

  protected notFound(): boolean { return isKind(this.q.error(), 'not_found'); }

  protected reloadLatest(): void { this.conflict.set(false); this.q.load(); }

  protected onUpdated(t: Ticket): void { this.q.set(t); this.activityTick.update(this.bump); }
}
