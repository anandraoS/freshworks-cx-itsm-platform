import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ToastService } from '../../core/toast.service';

/** Live region for transient messages (polite for success/info, assertive for errors). */
@Component({
  selector: 'app-toast-container',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toast-region" aria-live="polite" aria-atomic="false">
      @for (t of toasts.toasts(); track t.id) {
        <div [class]="'toast toast-' + t.kind" [attr.role]="t.kind === 'error' ? 'alert' : 'status'">
          <span>{{ t.message }}</span>
          <button type="button" class="toast-close" aria-label="Dismiss notification" (click)="toasts.dismiss(t.id)">×</button>
        </div>
      }
    </div>
  `,
})
export class ToastContainerComponent {
  protected readonly toasts = inject(ToastService);
}
