import { ChangeDetectionStrategy, Component, computed, DestroyRef, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { debounceTime, Subject } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { TicketApi, TicketFilters } from '../../core/endpoints';
import { describeError } from '../../core/errors';
import { formatDateTime, humanize } from '../../core/format';
import { loadable } from '../../core/loadable';
import { LookupsService } from '../../core/lookups.service';
import { PRIORITIES, SLA_STATUSES, STATUSES, TICKET_TYPES } from '../../core/models';
import { filtersFromParams, nextSort, paramsFromFilters } from '../../core/ticket-filters';
import { PriorityBadgeComponent, StatusBadgeComponent } from '../../shared/ui/badges';
import { ButtonComponent } from '../../shared/ui/button';
import { CardComponent } from '../../shared/ui/card';
import { EmptyStateComponent, ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';
import { PaginationComponent } from '../../shared/ui/pagination';
import { SlaIndicatorComponent } from '../../shared/ui/sla-indicator';
import { Column, TableComponent } from '../../shared/ui/table';

/** Server-side search/filter/sort/pagination; the URL query string is the single source of truth. */
@Component({
  selector: 'app-ticket-list-page',
  imports: [
    RouterLink, ButtonComponent, CardComponent, SkeletonComponent, ErrorNoticeComponent, EmptyStateComponent, PaginationComponent,
    TableComponent, PriorityBadgeComponent, StatusBadgeComponent, SlaIndicatorComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-head">
      <h1>{{ customer() ? 'My tickets' : 'Tickets' }}</h1>
      <a class="btn btn-primary btn-md" routerLink="/tickets/new">New ticket</a>
    </div>
    <app-card>
      <form class="filters" role="search" aria-label="Filter tickets" (submit)="$event.preventDefault()">
        <div class="field">
          <label for="f-q">Search</label>
          <input id="f-q" type="search" [value]="search()" (input)="onSearch($any($event.target).value)" placeholder="Title or description" />
        </div>
        <div class="field">
          <label for="f-status">Status</label>
          <select id="f-status" (change)="setFilter('status', $any($event.target).value)">
            <option value="">All</option>
            @for (s of statuses; track s) { <option [value]="s" [selected]="s === urlFilters().status">{{ human(s) }}</option> }
          </select>
        </div>
        <div class="field">
          <label for="f-priority">Priority</label>
          <select id="f-priority" (change)="setFilter('priority', $any($event.target).value)">
            <option value="">All</option>
            @for (s of priorities; track s) { <option [value]="s" [selected]="s === urlFilters().priority">{{ human(s) }}</option> }
          </select>
        </div>
        <div class="field">
          <label for="f-type">Type</label>
          <select id="f-type" (change)="setFilter('type', $any($event.target).value)">
            <option value="">All</option>
            @for (s of types; track s) { <option [value]="s" [selected]="s === urlFilters().type">{{ human(s) }}</option> }
          </select>
        </div>
        <div class="field">
          <label for="f-category">Category</label>
          <select id="f-category" (change)="setFilter('categoryId', $any($event.target).value)">
            <option value="">All</option>
            @for (c of lookups.categories(); track c.id) { <option [value]="c.id" [selected]="c.id === urlFilters().categoryId">{{ c.name }}</option> }
          </select>
        </div>
        <div class="field">
          <label for="f-sla">SLA</label>
          <select id="f-sla" (change)="setFilter('slaStatus', $any($event.target).value)">
            <option value="">All</option>
            @for (s of slaStatuses; track s) { <option [value]="s" [selected]="s === urlFilters().slaStatus">{{ human(s) }}</option> }
          </select>
        </div>
        @if (!customer()) {
          <div class="field">
            <label for="f-assignee">Assignee</label>
            <select id="f-assignee" (change)="setFilter('assigneeId', $any($event.target).value)">
              <option value="">Anyone</option>
              @for (u of lookups.users(); track u.id) { <option [value]="u.id" [selected]="u.id === urlFilters().assigneeId">{{ u.displayName }}</option> }
            </select>
          </div>
          <div class="field field-check">
            <label><input type="checkbox" [checked]="!!urlFilters().mine" (change)="update({ mine: $any($event.target).checked || undefined, page: 0 })" /> Assigned to me</label>
          </div>
        }
        @if (hasFilters()) { <button appButton variant="ghost" (click)="clear()">Clear filters</button> }
      </form>
    </app-card>

    <app-card cardClass="mt">
      @if (q.loading() && !q.data()) { <app-skeleton [lines]="8" label="Loading tickets" /> }
      @if (q.error(); as e) { <app-error-notice [message]="describe(e)" retryable (retry)="q.load()" /> }
      @if (q.data(); as p) {
        @if (p.content.length === 0) {
          <app-empty-state title="No tickets found" [description]="hasFilters() ? 'Try changing or clearing the filters.' : 'Create your first ticket to get started.'">
            <a class="btn btn-primary btn-md" routerLink="/tickets/new">New ticket</a>
          </app-empty-state>
        } @else {
          <div [attr.aria-busy]="q.loading()">
            <app-table caption="Tickets" [columns]="columns()" [rows]="p.content" [sort]="urlFilters().sort" sortable (sortChange)="onSort($event)">
              <ng-template #cell let-row let-col="col">
                @switch (col) {
                  @case ('number') { {{ row.number }} }
                  @case ('title') { <a [routerLink]="['/tickets', row.id]">{{ row.title }}</a> }
                  @case ('status') { <app-status-badge [status]="row.status" /> }
                  @case ('priority') { <app-priority-badge [priority]="row.priority" /> }
                  @case ('sla') { <app-sla-indicator [status]="row.slaStatus" [due]="row.resolutionDueAt" /> }
                  @case ('assignee') { @if (row.assignee) { {{ row.assignee.displayName }} } @else { <span class="muted">Unassigned</span> } }
                  @case ('updated') { {{ fmt(row.updatedAt) }} }
                }
              </ng-template>
            </app-table>
            <app-pagination [page]="p.page" [totalPages]="p.totalPages" [totalElements]="p.totalElements" (pageChange)="update({ page: $event })" />
          </div>
        }
      }
    </app-card>
  `,
})
export class TicketListPage {
  private readonly api = inject(TicketApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthService);
  protected readonly lookups = inject(LookupsService);

  protected readonly statuses = STATUSES;
  protected readonly priorities = PRIORITIES;
  protected readonly types = TICKET_TYPES;
  protected readonly slaStatuses = SLA_STATUSES;
  protected readonly human = humanize;
  protected readonly fmt = formatDateTime;
  protected readonly describe = describeError;
  protected readonly customer = this.auth.isCustomer;

  private readonly params = toSignal(this.route.queryParams, { initialValue: {} });
  protected readonly urlFilters = computed(() => filtersFromParams(this.params()));
  /** Customers only ever see their own tickets (the server enforces this too). */
  protected readonly filters = computed<TicketFilters>(() => (this.customer() ? { ...this.urlFilters(), mine: true } : this.urlFilters()));
  protected readonly q = loadable(() => this.api.list(this.filters()));

  protected readonly search = signal('');
  private readonly search$ = new Subject<string>();

  protected readonly hasFilters = computed(() => {
    const f = this.urlFilters();
    return !!(f.q || f.status || f.priority || f.assigneeId || f.categoryId || f.type || f.slaStatus || (!this.customer() && f.mine));
  });

  protected readonly columns = computed<Column[]>(() => [
    { key: 'number', header: '#', sortField: 'number' },
    { key: 'title', header: 'Title', sortField: 'title' },
    { key: 'status', header: 'Status', sortField: 'status' },
    { key: 'priority', header: 'Priority', sortField: 'priority' },
    { key: 'sla', header: 'SLA' },
    ...(this.customer() ? [] : [{ key: 'assignee', header: 'Assignee' }]),
    { key: 'updated', header: 'Updated', sortField: 'updatedAt' },
  ]);

  constructor() {
    this.lookups.ensureCategories();
    this.lookups.ensureUsers();
    // reload whenever the URL-derived filters change
    effect(() => {
      this.filters();
      untracked(() => this.q.load());
    });
    effect(() => { this.search.set(this.urlFilters().q ?? ''); });
    this.search$.pipe(debounceTime(300), takeUntilDestroyed(inject(DestroyRef))).subscribe((v) => {
      if (v !== (this.urlFilters().q ?? '')) this.update({ q: v || undefined, page: 0 });
    });
  }

  protected onSearch(v: string): void { this.search.set(v); this.search$.next(v); }

  protected update(patch: Partial<TicketFilters>): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: paramsFromFilters({ ...this.urlFilters(), ...patch }) });
  }
  protected setFilter(key: keyof TicketFilters, value: string): void {
    this.update({ [key]: value || undefined, page: 0 } as Partial<TicketFilters>);
  }
  protected onSort(field: string): void { this.update({ sort: nextSort(this.urlFilters().sort, field), page: 0 }); }
  protected clear(): void { this.search.set(''); void this.router.navigate([], { relativeTo: this.route, queryParams: {} }); }
}
