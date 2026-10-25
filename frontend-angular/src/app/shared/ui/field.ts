import { AbstractControl } from '@angular/forms';
import { booleanAttribute, Component, Directive, inject, input } from '@angular/core';
import { firstError, shouldShow } from '../../core/validators';

let seq = 0;

/**
 * Form field wrapper: label, hint and error message wired to the projected control via {@link FieldControlDirective}
 * (id, aria-describedby, aria-invalid). Errors appear after the control is touched/dirty (or the form is submitted).
 *
 *   <app-field label="Title" [control]="form.controls.title" required>
 *     <input appFieldControl formControlName="title" />
 *   </app-field>
 */
@Component({
  selector: 'app-field',
  template: `
    <div class="field" [class.field-error]="showError()">
      <label [attr.for]="id">{{ label() }}@if (required()) { <span aria-hidden="true"> *</span> }</label>
      <ng-content />
      @if (hint() && !showError()) { <p class="field-hint" [id]="id + '-hint'">{{ hint() }}</p> }
      @if (showError()) { <p class="field-msg" role="alert" [id]="id + '-err'">{{ message() }}</p> }
    </div>
  `,
})
export class FieldComponent {
  readonly label = input.required<string>();
  readonly control = input<AbstractControl | null>(null);
  readonly hint = input('');
  readonly required = input(false, { transform: booleanAttribute });
  readonly id = `fld-${++seq}`;

  showError(): boolean { return shouldShow(this.control()); }
  message(): string | null { return firstError(this.control()); }
  describedBy(): string | null {
    if (this.showError()) return this.id + '-err';
    return this.hint() ? this.id + '-hint' : null;
  }
}

@Directive({
  selector: '[appFieldControl]',
  host: {
    '[attr.id]': 'field.id',
    '[attr.aria-invalid]': 'field.showError() ? "true" : null',
    '[attr.aria-describedby]': 'field.describedBy()',
    '[attr.aria-required]': 'field.required() ? "true" : null',
  },
})
export class FieldControlDirective {
  protected readonly field = inject(FieldComponent);
}
