import { ChangeDetectionStrategy, Component, effect, inject, signal, untracked } from '@angular/core';
import { AuditApi } from '../../core/endpoints';
import { describeError } from '../../core/errors';
import { formatDateTime } from '../../core/format';
import { loadable } from '../../core/loadable';
import { CardComponent } from '../../shared/ui/card';
import { ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';
import { PaginationComponent } from '../../shared/ui/pagination';
import { Column, TableComponent } from '../../shared/ui/table';

@Component({
  selector: 'app-audit-page',
  imports: [CardComponent, TableComponent, PaginationComponent, SkeletonComponent, ErrorNoticeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-card title="Audit activity">
      <div class="filters">
        <div class="field">
          <label for="audit-entity">Entity type</label>
          <input id="audit-entity" placeholder="e.g. TICKET" [value]="entityType()" (input)="setEntity($any($event.target).value)" />
        </div>
      </div>
      @if (q.loading() && !q.data()) { <app-skeleton [lines]="6" label="Loading audit events" /> }
      @if (q.error(); as e) { <app-error-notice [message]="describe(e)" retryable (retry)="q.load()" /> }
      @if (q.data(); as p) {
        <app-table caption="Audit events" [columns]="columns" [rows]="p.content">
          <ng-template #cell let-e let-col="col">
            @switch (col) {
              @case ('at') { {{ fmt(e.at) }} }
              @case ('actor') { {{ e.actorEmail }} }
              @case ('action') { {{ e.action }} }
              @case ('entity') { {{ e.entityType }} {{ e.entityId ?? '' }} }
              @case ('details') { {{ e.details }} }
            }
          </ng-template>
        </app-table>
        <app-pagination [page]="p.page" [totalPages]="p.totalPages" [totalElements]="p.totalElements" (pageChange)="page.set($event)" />
      }
    </app-card>
  `,
})
export class AuditPage {
  private readonly api = inject(AuditApi);
  protected readonly describe = describeError;
  protected readonly fmt = formatDateTime;
  protected readonly columns: Column[] = [
    { key: 'at', header: 'When' }, { key: 'actor', header: 'Actor' }, { key: 'action', header: 'Action' },
    { key: 'entity', header: 'Entity' }, { key: 'details', header: 'Details' },
  ];
  protected readonly page = signal(0);
  protected readonly entityType = signal('');
  protected readonly q = loadable(() => this.api.list({ page: this.page(), size: 20, entityType: this.entityType() || undefined }));

  constructor() {
    effect(() => { this.page(); this.entityType(); untracked(() => this.q.load()); });
  }

  protected setEntity(v: string): void { this.entityType.set(v.toUpperCase()); this.page.set(0); }
}
