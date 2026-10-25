import { booleanAttribute, ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

@Component({
  selector: 'app-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="skeleton-wrap" role="status" [attr.aria-label]="label()" aria-busy="true">
      @for (w of widths(); track $index) { <div class="skeleton" [style.height.px]="height()" [style.width.%]="w"></div> }
    </div>
  `,
})
export class SkeletonComponent {
  readonly lines = input(3);
  readonly height = input(14);
  readonly label = input('Loading');
  protected readonly widths = computed(() => Array.from({ length: this.lines() }, (_, i) => 100 - (i % 3) * 12));
}

@Component({
  selector: 'app-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="empty" role="status">
      <p class="empty-title">{{ title() }}</p>
      @if (description()) { <p class="empty-desc">{{ description() }}</p> }
      <ng-content />
    </div>
  `,
})
export class EmptyStateComponent {
  readonly title = input.required<string>();
  readonly description = input('');
}

@Component({
  selector: 'app-error-notice',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="notice notice-error" role="alert">
      <span>{{ message() }}</span>
      @if (retryable()) { <button type="button" class="btn btn-secondary btn-sm" (click)="retry.emit()">Retry</button> }
    </div>
  `,
})
export class ErrorNoticeComponent {
  readonly message = input.required<string>();
  readonly retryable = input(false, { transform: booleanAttribute });
  readonly retry = output<void>();
}

@Component({
  selector: 'app-confidence-meter',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="meter" role="meter" aria-label="AI confidence" aria-valuemin="0" aria-valuemax="100"
         [attr.aria-valuenow]="pct()" [attr.aria-valuetext]="pct() + ' percent'">
      <div class="meter-bar"><div class="meter-fill" [style.width.%]="pct()"></div></div>
      <span class="meter-label">{{ pct() }}%</span>
    </div>
  `,
})
export class ConfidenceMeterComponent {
  readonly value = input.required<number>();
  protected readonly pct = computed(() => Math.max(0, Math.min(100, Math.round(this.value() * 100))));
}
