import { booleanAttribute, ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { ButtonComponent } from './button';
import { ModalComponent } from './modal';

/** Confirmation modal. Render with `@if`; message content is projected. */
@Component({
  selector: 'app-confirm-dialog',
  imports: [ModalComponent, ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-modal [title]="title()" (closed)="cancelled.emit()">
      <ng-content />
      <div class="modal-footer">
        <button appButton variant="secondary" data-autofocus (click)="cancelled.emit()">Cancel</button>
        <button appButton [variant]="danger() ? 'danger' : 'primary'" [loading]="busy()" (click)="confirmed.emit()">{{ confirmLabel() }}</button>
      </div>
    </app-modal>
  `,
})
export class ConfirmDialogComponent {
  readonly title = input.required<string>();
  readonly confirmLabel = input('Confirm');
  readonly danger = input(false, { transform: booleanAttribute });
  readonly busy = input(false);
  readonly confirmed = output<void>();
  readonly cancelled = output<void>();
}
