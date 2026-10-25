import { NgTemplateOutlet } from '@angular/common';
import { booleanAttribute, ChangeDetectionStrategy, Component, computed, contentChild, input, output, TemplateRef } from '@angular/core';

export interface Column {
  key: string;
  header: string;
  /** server-side sort field; when set the header becomes a sort button */
  sortField?: string;
}

export interface CellContext<T> { $implicit: T; col: string }

/**
 * Accessible data table. Cells are rendered by one projected template:
 * `<app-table ...><ng-template #cell let-row let-col="col">@switch (col) { ... }</ng-template></app-table>`
 */
@Component({
  selector: 'app-table',
  imports: [NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="table-wrap">
      <table class="table">
        <caption class="sr-only">{{ caption() }}</caption>
        <thead>
          <tr>
            @for (c of columns(); track c.key) {
              <th scope="col" [attr.aria-sort]="ariaSort(c)">
                @if (c.sortField && sortable()) {
                  <button type="button" class="th-sort" (click)="sortChange.emit(c.sortField)">
                    {{ c.header }}<span aria-hidden="true">{{ arrow(c) }}</span>
                  </button>
                } @else { {{ c.header }} }
              </th>
            }
          </tr>
        </thead>
        <tbody>
          @for (r of rows(); track trackBy()(r)) {
            <tr>
              @for (c of columns(); track c.key) {
                <td><ng-container [ngTemplateOutlet]="cell()" [ngTemplateOutletContext]="{ $implicit: r, col: c.key }" /></td>
              }
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class TableComponent<T = unknown> {
  readonly caption = input.required<string>();
  readonly columns = input.required<Column[]>();
  readonly rows = input.required<T[]>();
  readonly trackBy = input<(row: T) => string | number>((r) => (r as { id: string }).id);
  /** "field,dir" */
  readonly sort = input<string | undefined>(undefined);
  readonly sortable = input(false, { transform: booleanAttribute });
  readonly sortChange = output<string>();
  readonly cell = contentChild.required<TemplateRef<CellContext<T>>>('cell');

  private readonly parts = computed(() => (this.sort() ?? '').split(','));

  protected ariaSort(c: Column): 'ascending' | 'descending' | null {
    const [f, d] = this.parts();
    if (!c.sortField || c.sortField !== f) return null;
    return d === 'asc' ? 'ascending' : 'descending';
  }
  protected arrow(c: Column): string {
    const s = this.ariaSort(c);
    return s === 'ascending' ? ' ▲' : s === 'descending' ? ' ▼' : '';
  }
}
