import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { KbApi } from '../../core/endpoints';
import { describeError } from '../../core/errors';
import { authorName, formatDateTime } from '../../core/format';
import { loadable } from '../../core/loadable';
import { ToastService } from '../../core/toast.service';
import { BadgeComponent } from '../../shared/ui/badges';
import { ButtonComponent } from '../../shared/ui/button';
import { CardComponent } from '../../shared/ui/card';
import { EmptyStateComponent, ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';

@Component({
  selector: 'app-kb-detail-page',
  imports: [RouterLink, CardComponent, BadgeComponent, ButtonComponent, SkeletonComponent, ErrorNoticeComponent, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (q.loading() && !q.data()) { <app-skeleton [lines]="8" label="Loading article" /> }
    @if (q.error(); as e) { <app-error-notice [message]="describe(e)" retryable (retry)="q.load()" /> }
    @if (q.data(); as a) {
      <article>
        <div class="page-head">
          <div>
            <p class="muted small"><a routerLink="/kb">← Articles</a></p>
            <h1>{{ a.title }}</h1>
            <p class="muted small">{{ a.categoryName ?? 'Uncategorized' }} · {{ author(a.author) }} · updated {{ fmt(a.updatedAt) }} · v{{ a.version }}</p>
          </div>
          @if (staff()) {
            <div class="btn-row">
              <app-badge [tone]="a.status === 'PUBLISHED' ? 'success' : 'warn'">{{ a.status }}</app-badge>
              <app-badge [tone]="a.visibility === 'INTERNAL' ? 'internal' : 'neutral'">{{ a.visibility }}</app-badge>
              <a class="btn btn-secondary btn-md" [routerLink]="['/kb', a.id, 'edit']">Edit</a>
              @if (a.status === 'DRAFT') { <button appButton [loading]="publishing()" (click)="publish()">Publish</button> }
            </div>
          }
        </div>
        <app-card>
          @if (a.summary) { <p class="lead">{{ a.summary }}</p> }
          <div class="article-body">{{ a.body }}</div>
        </app-card>
        @if (staff()) {
          <app-card title="Version history" cardClass="mt">
            @if (versions.loading() && !versions.data()) { <app-skeleton [lines]="2" label="Loading versions" /> }
            @if (versions.error(); as e) { <app-error-notice [message]="describe(e)" /> }
            @if (versions.data()?.length === 0) { <app-empty-state title="No previous versions" /> }
            <ul class="plain-list">
              @for (v of versions.data(); track v.id + '-' + v.version) {
                <li>v{{ v.version }} · {{ v.title }} · {{ fmt(v.updatedAt) }} · <app-badge>{{ v.status }}</app-badge></li>
              }
            </ul>
            @if (a.relatedTicketIds.length > 0) {
              <p class="small">Linked tickets: @for (tid of a.relatedTicketIds; track tid) { <a [routerLink]="['/tickets', tid]">{{ tid.slice(0, 8) }} </a> }</p>
            }
          </app-card>
        }
      </article>
    }
  `,
})
export class KbDetailPage {
  private readonly auth = inject(AuthService);
  private readonly api = inject(KbApi);
  private readonly toast = inject(ToastService);
  readonly id = input.required<string>();
  protected readonly staff = () => this.auth.isStaff() && !this.auth.isCustomer();
  protected readonly q = loadable(() => this.api.get(this.id()));
  protected readonly versions = loadable(() => this.api.versions(this.id()));
  protected readonly publishing = signal(false);
  protected readonly describe = describeError;
  protected readonly fmt = formatDateTime;
  protected readonly author = authorName;

  constructor() {
    effect(() => {
      this.id();
      untracked(() => { this.q.load(); if (this.staff()) this.versions.load(); });
    });
  }

  protected publish(): void {
    this.publishing.set(true);
    this.api.publish(this.id()).subscribe({
      next: (a) => { this.publishing.set(false); this.q.set(a); this.versions.load(); this.toast.success('Article published'); },
      error: (e: unknown) => { this.publishing.set(false); this.toast.error(describeError(e)); },
    });
  }
}
