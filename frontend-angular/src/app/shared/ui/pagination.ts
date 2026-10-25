import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

@Component({
  selector: 'app-pagination',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (totalPages() > 1 || totalElements() !== undefined) {
      <nav class="pagination" aria-label="Pagination">
        <button type="button" class="btn btn-secondary btn-sm" [disabled]="page() <= 0" (click)="pageChange.emit(page() - 1)">Previous</button>
        <span aria-live="polite">Page {{ page() + 1 }} of {{ Math.max(totalPages(), 1) }}@if (totalElements() !== undefined) { · {{ totalElements() }} total }</span>
        <button type="button" class="btn btn-secondary btn-sm" [disabled]="page() + 1 >= totalPages()" (click)="pageChange.emit(page() + 1)">Next</button>
      </nav>
    }
  `,
})
export class PaginationComponent {
  protected readonly Math = Math;
  readonly page = input.required<number>();
  readonly totalPages = input.required<number>();
  readonly totalElements = input<number | undefined>(undefined);
  readonly pageChange = output<number>();
}
