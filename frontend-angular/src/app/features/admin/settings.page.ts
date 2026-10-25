import { Component, inject, OnInit, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { SettingsApi } from '../../core/endpoints';
import { describeError } from '../../core/errors';
import { buildSettingsForm } from '../../core/form-schemas';
import { loadable } from '../../core/loadable';
import { ToastService } from '../../core/toast.service';
import { applyFieldErrors, validateAll } from '../../core/validators';
import { ButtonComponent } from '../../shared/ui/button';
import { CardComponent } from '../../shared/ui/card';
import { ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';
import { FieldComponent, FieldControlDirective } from '../../shared/ui/field';

const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

@Component({
  selector: 'app-settings-page',
  imports: [ReactiveFormsModule, ButtonComponent, CardComponent, FieldComponent, FieldControlDirective, SkeletonComponent, ErrorNoticeComponent],
  template: `
    @if (q.loading() && !q.data()) { <app-skeleton [lines]="8" label="Loading settings" /> }
    @if (q.error(); as e) { <app-error-notice [message]="describe(e)" retryable (retry)="q.load()" /> }
    @if (q.data()) {
      <app-card title="Organization settings">
        <form [formGroup]="form" (ngSubmit)="save()" novalidate aria-label="Organization settings" class="form-grid">
          <app-field label="Organization name" [control]="form.controls.name" required><input appFieldControl formControlName="name" /></app-field>
          <app-field label="Timezone" hint="IANA name, e.g. UTC or America/New_York" [control]="form.controls.timezone" required><input appFieldControl formControlName="timezone" /></app-field>
          <app-field label="Business hours start" [control]="form.controls.businessHoursStart" required><input appFieldControl formControlName="businessHoursStart" type="time" /></app-field>
          <app-field label="Business hours end" [control]="form.controls.businessHoursEnd" required><input appFieldControl formControlName="businessHoursEnd" type="time" /></app-field>
          <app-field label="Default language" [control]="form.controls.defaultLanguage" required><input appFieldControl formControlName="defaultLanguage" /></app-field>
          <fieldset class="span-2 fieldset">
            <legend>Business days</legend>
            <div class="check-row">
              @for (d of days; track d) {
                <label><input type="checkbox" [checked]="hasDay(d)" (change)="toggleDay(d, $any($event.target).checked)" /> {{ label(d) }}</label>
              }
            </div>
            @if (daysError()) { <p class="field-msg" role="alert">Select at least one business day</p> }
          </fieldset>
          <fieldset class="span-2 fieldset">
            <legend>Integrations (dummy values only)</legend>
            <p class="muted small">Use placeholder values for demos. Never enter real credentials or production webhook URLs here.</p>
            <div class="form-grid">
              <app-field label="Email provider" [control]="form.controls.emailProvider"><input appFieldControl formControlName="emailProvider" placeholder="LOG_ONLY" /></app-field>
              <app-field label="AI provider" [control]="form.controls.aiProvider"><input appFieldControl formControlName="aiProvider" placeholder="MOCK_RULE_BASED" /></app-field>
              <div class="span-2"><app-field label="Slack webhook URL" [control]="form.controls.slackWebhookUrl"><input appFieldControl formControlName="slackWebhookUrl" type="url" placeholder="https://example.invalid/webhook" /></app-field></div>
            </div>
          </fieldset>
          @if (formError(); as m) { <p class="notice notice-error span-2" role="alert">{{ m }}</p> }
          <div class="span-2 form-actions"><button appButton type="submit" [loading]="busy()" [disabled]="!form.dirty">Save settings</button></div>
        </form>
      </app-card>
    }
  `,
})
export class SettingsPage implements OnInit {
  private readonly api = inject(SettingsApi);
  private readonly toast = inject(ToastService);
  protected readonly days = DAYS;
  protected readonly form = buildSettingsForm();
  protected readonly busy = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly describe = describeError;
  protected readonly q = loadable(() => this.api.get(), (s) => {
    this.form.reset({
      name: s.name, timezone: s.timezone, businessHoursStart: s.businessHoursStart, businessHoursEnd: s.businessHoursEnd,
      businessDays: s.businessDays, defaultLanguage: s.defaultLanguage,
      emailProvider: s.integrations?.emailProvider ?? '', slackWebhookUrl: s.integrations?.slackWebhookUrl ?? '', aiProvider: s.integrations?.aiProvider ?? '',
    });
  });

  ngOnInit(): void { this.q.load(); }

  protected label(d: string): string { return d.charAt(0) + d.slice(1).toLowerCase(); }
  protected hasDay(d: string): boolean { return this.form.controls.businessDays.value.includes(d); }
  protected daysError(): boolean { const c = this.form.controls.businessDays; return c.invalid && (c.touched || c.dirty); }
  protected toggleDay(d: string, on: boolean): void {
    const c = this.form.controls.businessDays;
    c.setValue(on ? [...c.value, d] : c.value.filter((x) => x !== d));
    c.markAsDirty(); c.markAsTouched();
  }

  protected save(): void {
    if (this.busy() || !validateAll(this.form)) return;
    const v = this.form.getRawValue();
    this.busy.set(true);
    this.formError.set(null);
    this.api.update({
      name: v.name.trim(), timezone: v.timezone.trim(), businessHoursStart: v.businessHoursStart, businessHoursEnd: v.businessHoursEnd,
      businessDays: v.businessDays, defaultLanguage: v.defaultLanguage.trim(),
      integrations: { emailProvider: v.emailProvider.trim(), slackWebhookUrl: v.slackWebhookUrl.trim(), aiProvider: v.aiProvider.trim() },
    }).subscribe({
      next: () => { this.busy.set(false); this.form.markAsPristine(); this.toast.success('Settings saved'); },
      error: (e: unknown) => {
        this.busy.set(false);
        if (!applyFieldErrors(e, this.form, ['name', 'timezone', 'businessHoursStart', 'businessHoursEnd', 'defaultLanguage'])) this.formError.set(describeError(e));
      },
    });
  }
}
