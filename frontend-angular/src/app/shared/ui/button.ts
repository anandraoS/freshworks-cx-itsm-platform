import { booleanAttribute, ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost';

/** `<button appButton variant="secondary" size="sm" [loading]="busy">`; shows a spinner and disables itself while loading. */
@Component({
  // attribute-selector component on purpose (keeps the native <button> element for a11y and forms)
  // eslint-disable-next-line @angular-eslint/component-selector
  selector: 'button[appButton]',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class]': 'cls()',
    '[attr.type]': 'type()',
    '[disabled]': 'disabled() || loading()',
    '[attr.aria-busy]': 'loading() ? "true" : null',
  },
  template: `@if (loading()) { <span class="spinner" aria-hidden="true"></span> }<ng-content />`,
})
export class ButtonComponent {
  readonly variant = input<ButtonVariant>('primary');
  readonly size = input<'sm' | 'md'>('md');
  readonly loading = input(false, { transform: booleanAttribute });
  readonly disabled = input(false, { transform: booleanAttribute });
  readonly type = input<'button' | 'submit'>('button');
  protected readonly cls = computed(() => `btn btn-${this.variant()} btn-${this.size()}`);
}
