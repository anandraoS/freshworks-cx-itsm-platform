import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ReactiveFormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';
import { WorkflowApi } from '../../core/endpoints';
import { describeError } from '../../core/errors';
import {
  buildAction, buildCondition, buildRule, buildTransition, buildWorkflowForm, defaultWorkflowForm, fromWorkflow,
  toWorkflowInput, WorkflowFormValue,
} from '../../core/form-schemas';
import { formatDateTime, humanize } from '../../core/format';
import { loadable } from '../../core/loadable';
import { STATUSES, WORKFLOW_ACTIONS, WORKFLOW_OPS } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { firstError, shouldShow } from '../../core/validators';
import { BadgeComponent } from '../../shared/ui/badges';
import { ButtonComponent } from '../../shared/ui/button';
import { CardComponent } from '../../shared/ui/card';
import { EmptyStateComponent, ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';
import { FieldComponent, FieldControlDirective } from '../../shared/ui/field';

/** Workflow versions + editor. Flow: edit -> Validate (server) -> Save as new version -> Activate. Any edit voids the last validation. */
@Component({
  selector: 'app-workflows-page',
  imports: [ReactiveFormsModule, ButtonComponent, CardComponent, BadgeComponent, FieldComponent, FieldControlDirective, SkeletonComponent, ErrorNoticeComponent, EmptyStateComponent],
  template: `
    <div class="stack">
      <app-card title="Workflow versions">
        @if (q.loading() && !q.data()) { <app-skeleton [lines]="4" label="Loading workflows" /> }
        @if (q.error(); as e) { <app-error-notice [message]="describe(e)" retryable (retry)="q.load()" /> }
        @if (q.data(); as list) {
          @if (list.length === 0) { <app-empty-state title="No workflows yet" [description]="canEdit() ? 'Create the first version below.' : ''" /> }
          <ul class="plain-list">
            @for (w of list; track w.id) {
              <li class="wf-row">
                <span><strong>{{ w.name }}</strong> · v{{ w.version }} · {{ w.rules.length }} rules · {{ w.transitions.length }} transitions · <span class="muted small">{{ fmt(w.createdAt) }}</span>
                  @if (w.active) { <app-badge tone="success">Active</app-badge> }</span>
                @if (canEdit()) {
                  <span class="btn-row">
                    <button appButton size="sm" variant="secondary" (click)="useAsTemplate(w)">Use as template</button>
                    @if (!w.active) { <button appButton size="sm" [loading]="activatingId() === w.id" (click)="activate(w.id)">Activate</button> }
                  </span>
                }
              </li>
            }
          </ul>
        }
      </app-card>

      @if (canEdit()) {
        <app-card title="Workflow editor">
          <form [formGroup]="form" (ngSubmit)="validate()" novalidate aria-label="Workflow editor">
            <app-field label="Workflow name" [control]="form.controls.name" required><input appFieldControl formControlName="name" /></app-field>

            <h3>Automation rules</h3>
            @if (form.controls.rules.length === 0) { <p class="muted">No rules. Add a rule to automate assignment, priority, escalation, notifications or tagging.</p> }
            @for (rule of form.controls.rules.controls; track rule; let i = $index) {
              <fieldset class="fieldset rule" [formGroup]="rule">
                <legend>Rule {{ i + 1 }}</legend>
                <app-field [label]="'Rule name'" [control]="rule.controls.name"><input appFieldControl formControlName="name" /></app-field>
                <p class="small"><strong>When</strong> (all conditions match)</p>
                @for (c of rule.controls.when.controls; track c; let j = $index) {
                  <div class="row-inline" [formGroup]="c">
                    <app-field [label]="'Field ' + (j + 1)" [control]="c.controls.field"><input appFieldControl formControlName="field" placeholder="priority" /></app-field>
                    <app-field [label]="'Operator ' + (j + 1)"><select appFieldControl formControlName="op">@for (o of ops; track o) { <option [value]="o">{{ o }}</option> }</select></app-field>
                    <app-field [label]="'Value ' + (j + 1)" [control]="c.controls.value"><input appFieldControl formControlName="value" /></app-field>
                    <button appButton size="sm" variant="ghost" [attr.aria-label]="'Remove condition ' + (j + 1) + ' of rule ' + (i + 1)" (click)="rule.controls.when.removeAt(j)">Remove</button>
                  </div>
                }
                <button appButton size="sm" variant="secondary" (click)="rule.controls.when.push(newCondition())">Add condition</button>
                <p class="small"><strong>Then</strong></p>
                @for (a of rule.controls.then.controls; track a; let j = $index) {
                  <div class="row-inline" [formGroup]="a">
                    <app-field [label]="'Action ' + (j + 1)"><select appFieldControl formControlName="action">@for (x of actions; track x) { <option [value]="x">{{ human(x) }}</option> }</select></app-field>
                    <app-field [label]="'Parameter ' + (j + 1)"><input appFieldControl formControlName="param" /></app-field>
                    <button appButton size="sm" variant="ghost" [attr.aria-label]="'Remove action ' + (j + 1) + ' of rule ' + (i + 1)" (click)="rule.controls.then.removeAt(j)">Remove</button>
                  </div>
                }
                <button appButton size="sm" variant="secondary" (click)="rule.controls.then.push(newAction())">Add action</button>
                @if (arrayError(rule.controls.when); as m) { <p class="field-msg" role="alert">{{ m }}</p> }
                @if (arrayError(rule.controls.then); as m) { <p class="field-msg" role="alert">{{ m }}</p> }
                <div><button appButton size="sm" variant="danger" (click)="form.controls.rules.removeAt(i)">Remove rule {{ i + 1 }}</button></div>
              </fieldset>
            }
            <button appButton variant="secondary" size="sm" (click)="addRule()">Add rule</button>

            <h3>Allowed transitions</h3>
            @for (t of form.controls.transitions.controls; track t; let i = $index) {
              <div class="row-inline" [formGroup]="t">
                <app-field [label]="'Transition ' + (i + 1) + ' from'"><select appFieldControl formControlName="from">@for (s of statuses; track s) { <option [value]="s">{{ human(s) }}</option> }</select></app-field>
                <span aria-hidden="true">→</span>
                <app-field [label]="'Transition ' + (i + 1) + ' to'"><select appFieldControl formControlName="to">@for (s of statuses; track s) { <option [value]="s">{{ human(s) }}</option> }</select></app-field>
                <button appButton size="sm" variant="ghost" [attr.aria-label]="'Remove transition ' + (i + 1)" (click)="form.controls.transitions.removeAt(i)">Remove</button>
              </div>
            }
            @if (form.controls.transitions.errors?.['same']; as err) { <p class="field-msg" role="alert">{{ err.message }}</p> }
            <button appButton variant="secondary" size="sm" (click)="form.controls.transitions.push(newTransition())">Add transition</button>

            @if (error(); as m) { <p class="notice notice-error" role="alert">{{ m }}</p> }
            @if (validation(); as v) {
              @if (v.valid) { <p class="notice notice-success" role="status">Validation passed. You can save this as a new version.</p> }
              @else {
                <div class="notice notice-error" role="alert"><strong>Validation failed</strong><ul>@for (m of v.errors; track m) { <li>{{ m }}</li> }</ul></div>
              }
            }
            <div class="form-actions">
              <button appButton type="submit" variant="secondary" [loading]="validating()">1. Validate</button>
              <button appButton [disabled]="!validation()?.valid" [loading]="saving()" (click)="save()">2. Save as new version</button>
            </div>
          </form>
        </app-card>
      } @else { <p class="muted">Workflow editing requires the ADMIN role.</p> }
    </div>
  `,
})
export class WorkflowsPage implements OnInit {
  private readonly api = inject(WorkflowApi);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly canEdit = this.auth.isAdmin;
  protected readonly statuses = STATUSES;
  protected readonly ops = WORKFLOW_OPS;
  protected readonly actions = WORKFLOW_ACTIONS;
  protected readonly human = humanize;
  protected readonly fmt = formatDateTime;
  protected readonly describe = describeError;
  protected readonly newCondition = () => buildCondition();
  protected readonly newAction = () => buildAction();
  protected readonly newTransition = () => buildTransition();

  protected readonly q = loadable(() => this.api.list());
  protected form = buildWorkflowForm(defaultWorkflowForm());
  protected readonly validation = signal<{ valid: boolean; errors: string[] } | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly validating = signal(false);
  protected readonly saving = signal(false);
  protected readonly activatingId = signal<string | null>(null);

  ngOnInit(): void { this.q.load(); this.watch(); }

  /** Any edit invalidates the previous server validation: the user must validate again before saving. */
  private watch(): void {
    this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.validation.set(null));
  }

  protected arrayError(arr: { errors: unknown; touched: boolean; dirty: boolean }): string | null {
    const err = arr.errors as { min?: { message: string } } | null;
    return err?.min && (arr.touched || arr.dirty) ? err.min.message : null;
  }
  protected shown = shouldShow;
  protected first = firstError;

  protected addRule(): void { this.form.controls.rules.push(buildRule()); }

  protected useAsTemplate(w: Parameters<typeof fromWorkflow>[0]): void {
    this.replaceForm(fromWorkflow(w));
  }
  private replaceForm(v: WorkflowFormValue): void {
    this.form = buildWorkflowForm(v);
    this.validation.set(null);
    this.watch();
  }

  protected validate(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.validating()) return;
    this.error.set(null);
    this.validating.set(true);
    this.api.validate(toWorkflowInput(this.form.getRawValue() as WorkflowFormValue)).subscribe({
      next: (r) => { this.validating.set(false); this.validation.set({ valid: r.valid, errors: r.errors ?? [] }); },
      error: (e: unknown) => { this.validating.set(false); this.error.set(describeError(e)); },
    });
  }

  protected save(): void {
    if (!this.validation()?.valid || this.saving()) return;
    this.saving.set(true);
    this.api.create(toWorkflowInput(this.form.getRawValue() as WorkflowFormValue)).subscribe({
      next: (w) => {
        this.saving.set(false);
        this.q.load();
        this.toast.success(`Saved as version ${w.version}. Activate it from the list above.`);
        this.validation.set(null);
      },
      error: (e: unknown) => { this.saving.set(false); this.error.set(describeError(e)); },
    });
  }

  protected activate(id: string): void {
    this.activatingId.set(id);
    this.api.activate(id).subscribe({
      next: () => { this.activatingId.set(null); this.q.load(); this.toast.success('Workflow activated'); },
      error: (e: unknown) => { this.activatingId.set(null); this.toast.error(describeError(e)); },
    });
  }
}
