import { Component, inject, OnInit, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { SlaApi } from '../../core/endpoints';
import { describeError } from '../../core/errors';
import { buildSlaForm, parsePercents } from '../../core/form-schemas';
import { formatMinutes } from '../../core/format';
import { loadable } from '../../core/loadable';
import type { SlaPolicy } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { validateAll } from '../../core/validators';
import { PriorityBadgeComponent } from '../../shared/ui/badges';
import { ButtonComponent } from '../../shared/ui/button';
import { CardComponent } from '../../shared/ui/card';
import { EmptyStateComponent, ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';
import { FieldComponent, FieldControlDirective } from '../../shared/ui/field';
import { ModalComponent } from '../../shared/ui/modal';
import { Column, TableComponent } from '../../shared/ui/table';

@Component({
  selector: 'app-sla-page',
  imports: [ReactiveFormsModule, ButtonComponent, CardComponent, ModalComponent, TableComponent, PriorityBadgeComponent, FieldComponent, FieldControlDirective, SkeletonComponent, ErrorNoticeComponent, EmptyStateComponent],
  template: `
    <app-card title="SLA policies">
      <p class="muted small">Business hours come from the organization settings.</p>
      @if (q.loading() && !q.data()) { <app-skeleton [lines]="4" label="Loading SLA policies" /> }
      @if (q.error(); as e) { <app-error-notice [message]="describe(e)" retryable (retry)="q.load()" /> }
      @if (q.data(); as list) {
        @if (list.length === 0) { <app-empty-state title="No SLA policies" /> }
        @else {
          <app-table caption="SLA policies" [columns]="columns" [rows]="list">
            <ng-template #cell let-p let-col="col">
              @switch (col) {
                @case ('p') { <app-priority-badge [priority]="p.priority" /> }
                @case ('fr') { {{ mins(p.firstResponseMinutes) }} }
                @case ('res') { {{ mins(p.resolutionMinutes) }} }
                @case ('esc') { {{ escalation(p) }} }
                @case ('a') { <button appButton size="sm" variant="secondary" [attr.aria-label]="'Edit ' + p.priority + ' policy'" (click)="open(p)">Edit</button> }
              }
            </ng-template>
          </app-table>
        }
      }
      @if (editing(); as p) {
        <app-modal [title]="'Edit ' + p.priority + ' SLA policy'" (closed)="editing.set(null)">
          <form [formGroup]="form" (ngSubmit)="save(p)" novalidate aria-label="SLA policy form">
            <app-field label="First response (minutes)" [control]="form.controls.firstResponseMinutes" required><input appFieldControl formControlName="firstResponseMinutes" type="number" min="1" data-autofocus /></app-field>
            <app-field label="Resolution (minutes)" [control]="form.controls.resolutionMinutes" required><input appFieldControl formControlName="resolutionMinutes" type="number" min="1" /></app-field>
            <app-field label="Escalate after % of time elapsed" hint="Comma separated, e.g. 50, 80" [control]="form.controls.escalationAfterPercent"><input appFieldControl formControlName="escalationAfterPercent" /></app-field>
            @if (formError(); as m) { <p class="notice notice-error" role="alert">{{ m }}</p> }
            <div class="modal-footer">
              <button appButton variant="secondary" (click)="editing.set(null)">Cancel</button>
              <button appButton type="submit" [loading]="busy()">Save</button>
            </div>
          </form>
        </app-modal>
      }
    </app-card>
  `,
})
export class SlaPage implements OnInit {
  private readonly api = inject(SlaApi);
  private readonly toast = inject(ToastService);
  protected readonly mins = formatMinutes;
  protected readonly describe = describeError;
  protected readonly columns: Column[] = [
    { key: 'p', header: 'Priority' }, { key: 'fr', header: 'First response' }, { key: 'res', header: 'Resolution' },
    { key: 'esc', header: 'Escalate at' }, { key: 'a', header: 'Actions' },
  ];
  protected readonly q = loadable(() => this.api.list());
  protected readonly editing = signal<SlaPolicy | null>(null);
  protected readonly busy = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected form = buildSlaForm();

  ngOnInit(): void { this.q.load(); }

  protected escalation(p: SlaPolicy): string { return (p.escalationAfterPercent ?? []).map((x) => `${x}%`).join(', ') || '—'; }

  protected open(p: SlaPolicy): void {
    this.formError.set(null);
    this.form = buildSlaForm(p);
    this.editing.set(p);
  }

  protected save(p: SlaPolicy): void {
    if (this.busy() || !validateAll(this.form)) return;
    const v = this.form.getRawValue();
    this.busy.set(true);
    this.api.update(p.id, {
      priority: p.priority, firstResponseMinutes: Number(v.firstResponseMinutes), resolutionMinutes: Number(v.resolutionMinutes),
      escalationAfterPercent: parsePercents(v.escalationAfterPercent),
    }).subscribe({
      next: () => { this.busy.set(false); this.editing.set(null); this.q.load(); this.toast.success('SLA policy saved'); },
      error: (e: unknown) => { this.busy.set(false); this.formError.set(describeError(e)); },
    });
  }
}
