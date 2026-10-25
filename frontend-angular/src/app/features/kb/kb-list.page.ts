import { ChangeDetectionStrategy, Component, computed, DestroyRef, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import { debounceTime, Subject } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { ArticleFilters, KbApi } from '../../core/endpoints';
import { describeError } from '../../core/errors';
import { authorName, formatDateTime } from '../../core/format';
import { loadable } from '../../core/loadable';
import { LookupsService } from '../../core/lookups.service';
import { BadgeComponent } from '../../shared/ui/badges';
import { CardComponent } from '../../shared/ui/card';
import { EmptyStateComponent, ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';
import { PaginationComponent } from '../../shared/ui/pagination';

@Component({
  selector: 'app-kb-list-page',
  imports: [RouterLink, CardComponent, BadgeComponent, SkeletonComponent, ErrorNoticeComponent, EmptyStateComponent, PaginationComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-head">
      <h1>{{ customer() ? 'Help articles' : 'Knowledge base' }}</h1>
      @if (auth.isStaff() && !customer()) { <a class="btn btn-primary btn-md" routerLink="/kb/new">New article</a> }
    </div>
    <app-card>
      <form class="filters" role="search" aria-label="Filter articles" (submit)="$event.preventDefault()">
        <div class="field">
          <label for="kb-q">Search</label>
          <input id="kb-q" type="search" [value]="search()" (input)="onSearch($any($event.target).value)" placeholder="Search articles" />
        </div>
        <div class="field">
          <label for="kb-cat">Category</label>
          <select id="kb-cat" (change)="update({ categoryId: $any($event.target).value || undefined, page: undefined })">
            <option value="">All</option>
            @for (c of lookups.articleCategories(); track c.id) { <option [value]="c.id" [selected]="c.id === categoryId()">{{ c.name }}</option> }
          </select>
        </div>
        @if (!customer()) {
          <div class="field">
            <label for="kb-status">Status</label>
            <select id="kb-status" (change)="update({ status: $any($event.target).value || undefined, page: undefined })">
              <option value="" [selected]="!status()">All</option>
              <option value="DRAFT" [selected]="status() === 'DRAFT'">Draft</option>
              <option value="PUBLISHED" [selected]="status() === 'PUBLISHED'">Published</option>
            </select>
          </div>
        }
      </form>
    </app-card>
    <app-card cardClass="mt">
      @if (query.loading() && !query.data()) { <app-skeleton [lines]="6" label="Loading articles" /> }
      @if (query.error(); as e) { <app-error-notice [message]="describe(e)" retryable (retry)="query.load()" /> }
      @if (query.data(); as p) {
        @if (p.content.length === 0) { <app-empty-state title="No articles found" description="Try a different search or category." /> }
        <ul class="article-list">
          @for (a of p.content; track a.id) {
            <li>
              <h2 class="article-title"><a [routerLink]="['/kb', a.id]">{{ a.title }}</a></h2>
              <p>{{ a.summary }}</p>
              <p class="small muted">
                {{ a.categoryName ?? 'Uncategorized' }} · {{ author(a.author) }} · {{ fmt(a.updatedAt) }}
                @if (!customer()) {
                  <app-badge [tone]="a.status === 'PUBLISHED' ? 'success' : 'warn'">{{ a.status }}</app-badge>
                  <app-badge [tone]="a.visibility === 'INTERNAL' ? 'internal' : 'neutral'">{{ a.visibility }}</app-badge>
                }
              </p>
            </li>
          }
        </ul>
        <app-pagination [page]="p.page" [totalPages]="p.totalPages" [totalElements]="p.totalElements" (pageChange)="update({ page: $event ? String($event) : undefined })" />
      }
    </app-card>
  `,
})
export class KbListPage {
  protected readonly auth = inject(AuthService);
  protected readonly lookups = inject(LookupsService);
  private readonly api = inject(KbApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly customer = this.auth.isCustomer;
  protected readonly String = String;
  protected readonly describe = describeError;
  protected readonly fmt = formatDateTime;
  protected readonly author = authorName;

  private readonly params = toSignal(this.route.queryParams, { initialValue: {} as Params });
  private readonly val = (k: string) => computed(() => (this.params()[k] as string | undefined) ?? '');
  protected readonly q = this.val('q');
  protected readonly categoryId = this.val('categoryId');
  protected readonly status = this.val('status');
  protected readonly page = computed(() => Math.max(0, parseInt((this.params()['page'] as string | undefined) ?? '0', 10) || 0));
  protected readonly filters = computed<ArticleFilters>(() => ({
    page: this.page(), size: 10, q: this.q() || undefined, categoryId: this.categoryId() || undefined,
    status: this.customer() ? 'PUBLISHED' : this.status() || undefined, visibility: this.customer() ? 'PUBLIC' : undefined,
  }));
  protected readonly query = loadable(() => this.api.list(this.filters()));

  protected readonly search = signal('');
  private readonly search$ = new Subject<string>();

  constructor() {
    this.lookups.ensureArticleCategories();
    effect(() => { this.filters(); untracked(() => this.query.load()); });
    effect(() => { this.search.set(this.q()); });
    this.search$.pipe(debounceTime(300), takeUntilDestroyed(inject(DestroyRef))).subscribe((v) => {
      if (v !== this.q()) this.update({ q: v || undefined, page: undefined });
    });
  }

  protected onSearch(v: string): void { this.search.set(v); this.search$.next(v); }

  protected update(patch: Record<string, string | undefined>): void {
    const next: Params = {};
    for (const [k, v] of Object.entries({ ...this.params(), ...patch })) if (v) next[k] = v;
    void this.router.navigate([], { relativeTo: this.route, queryParams: next });
  }
}
