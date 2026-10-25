import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { newId } from '../../core/correlation';
import { TicketApi } from '../../core/endpoints';
import { describeError, isKind } from '../../core/errors';
import { buildTicketForm, parseTags } from '../../core/form-schemas';
import { humanize } from '../../core/format';
import { loadable } from '../../core/loadable';
import { LookupsService } from '../../core/lookups.service';
import { Attachment, PRIORITIES, SEVERITIES, TICKET_TYPES } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { applyFieldErrors, validateAll } from '../../core/validators';
import { ButtonComponent } from '../../shared/ui/button';
import { CardComponent } from '../../shared/ui/card';
import { ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';
import { FieldComponent, FieldControlDirective } from '../../shared/ui/field';

export const MAX_ATTACHMENTS = 5;
export const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;
const FIELDS = ['type', 'title', 'description', 'priority', 'severity', 'categoryId', 'tags'];

/** Create + edit. Create sends ONE Idempotency-Key per form instance (reused on retry) and blocks double submits. */
@Component({
  selector: 'app-ticket-form-page',
  imports: [ReactiveFormsModule, RouterLink, ButtonComponent, CardComponent, FieldComponent, FieldControlDirective, SkeletonComponent, ErrorNoticeComponent],
  template: `
    @if (editing() && existing.loading() && !existing.data()) { <app-skeleton [lines]="8" label="Loading ticket" /> }
    @else if (editing() && existing.error(); as e) { <app-error-notice [message]="describe(e)" /> }
    @else {
      <div class="page-head"><h1>{{ editing() ? 'Edit ticket #' + (existing.data()?.number ?? '') : 'New ticket' }}</h1></div>
      <app-card>
        @if (conflict()) {
          <div class="notice notice-warn" role="alert">
            <span>Someone else changed this ticket while you were editing. Reload the latest version (your edits on this form will be replaced).</span>
            <button appButton size="sm" (click)="reloadLatest()">Reload latest</button>
          </div>
        }
        <form [formGroup]="form" (ngSubmit)="submit()" novalidate [attr.aria-label]="editing() ? 'Edit ticket' : 'Create ticket'" class="form-grid">
          @if (editing()) {
            <div class="field"><span class="label-like">Type</span><p>{{ human(existing.data()?.type ?? 'SUPPORT') }}</p></div>
          } @else {
            <app-field label="Type" [control]="form.controls.type" required>
              <select appFieldControl formControlName="type">
                @for (x of types; track x) { <option [value]="x">{{ human(x) }}</option> }
              </select>
            </app-field>
          }
          <app-field label="Category" [control]="form.controls.categoryId">
            <select appFieldControl formControlName="categoryId">
              <option value="">No category</option>
              @for (c of lookups.categories(); track c.id) { <option [value]="c.id">{{ c.name }}</option> }
            </select>
          </app-field>
          <div class="span-2">
            <app-field label="Title" [control]="form.controls.title" required>
              <input appFieldControl formControlName="title" maxlength="200" />
            </app-field>
          </div>
          <div class="span-2">
            <app-field label="Description" [control]="form.controls.description" required>
              <textarea appFieldControl formControlName="description" rows="6"></textarea>
            </app-field>
          </div>
          <app-field label="Priority" [control]="form.controls.priority" required>
            <select appFieldControl formControlName="priority">
              @for (x of priorities; track x) { <option [value]="x">{{ human(x) }}</option> }
            </select>
          </app-field>
          <app-field label="Severity" [control]="form.controls.severity" required>
            <select appFieldControl formControlName="severity">
              @for (x of severities; track x) { <option [value]="x">{{ human(x) }}</option> }
            </select>
          </app-field>
          <div class="span-2">
            <app-field label="Tags" hint="Comma separated, up to 10" [control]="form.controls.tags">
              <input appFieldControl formControlName="tags" />
            </app-field>
          </div>
          @if (!editing()) {
            <div class="span-2 field">
              <label for="attachments">Attachments (metadata only in this demo)</label>
              <input id="attachments" type="file" multiple (change)="onFiles($event)" />
              @if (attachError(); as m) { <p class="field-msg" role="alert">{{ m }}</p> }
              @if (attachments().length > 0) {
                <ul class="chips">
                  @for (a of attachments(); track $index) {
                    <li>{{ a.fileName }} <button type="button" class="link-btn" [attr.aria-label]="'Remove ' + a.fileName" (click)="removeAttachment($index)">×</button></li>
                  }
                </ul>
              }
            </div>
          }
          @if (formError(); as m) { <p class="notice notice-error span-2" role="alert">{{ m }}</p> }
          <div class="span-2 form-actions">
            <button appButton type="submit" [loading]="busy()">{{ editing() ? 'Save changes' : 'Create ticket' }}</button>
            <a class="btn btn-secondary btn-md" [routerLink]="editing() ? ['/tickets', id()] : ['/tickets']">Cancel</a>
          </div>
        </form>
      </app-card>
    }
  `,
})
export class TicketFormPage {
  private readonly api = inject(TicketApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  protected readonly lookups = inject(LookupsService);

  /** route param (withComponentInputBinding); present on /tickets/:id/edit */
  readonly id = input<string | undefined>(undefined);
  protected editing = () => !!this.id();

  protected readonly types = TICKET_TYPES;
  protected readonly priorities = PRIORITIES;
  protected readonly severities = SEVERITIES;
  protected readonly human = humanize;
  protected readonly describe = describeError;

  /** One Idempotency-Key per form instance; reused for every retry so the server replays the original response. */
  readonly idempotencyKey = newId();
  private submitting = false; // synchronous guard against a double click before the next render

  protected readonly form = buildTicketForm();
  protected readonly busy = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly conflict = signal(false);
  protected readonly attachments = signal<Attachment[]>([]);
  protected readonly attachError = signal<string | null>(null);
  protected readonly existing = loadable(
    () => this.api.get(this.id() as string),
    (tk) => this.form.reset({ type: tk.type, title: tk.title, description: tk.description, priority: tk.priority, severity: tk.severity, categoryId: tk.categoryId ?? '', tags: tk.tags.join(', ') }),
  );

  constructor() {
    this.lookups.ensureCategories();
    effect(() => {
      if (this.id()) untracked(() => this.loadExisting());
    });
  }

  private loadExisting(): void { this.existing.load(); }

  protected reloadLatest(): void { this.conflict.set(false); this.loadExisting(); }

  protected onFiles(e: Event): void {
    const input = e.target as HTMLInputElement;
    this.attachError.set(null);
    const next = [...this.attachments()];
    for (const f of Array.from(input.files ?? [])) {
      if (next.length >= MAX_ATTACHMENTS) { this.attachError.set(`At most ${MAX_ATTACHMENTS} attachments`); break; }
      if (f.size > MAX_ATTACHMENT_BYTES) { this.attachError.set(`${f.name} is larger than 10 MB`); continue; }
      next.push({ fileName: f.name, contentType: f.type || 'application/octet-stream', sizeBytes: f.size });
    }
    this.attachments.set(next);
    input.value = '';
  }
  protected removeAttachment(i: number): void { this.attachments.update((l) => l.filter((_, j) => j !== i)); }

  protected submit(): void {
    if (this.submitting || this.busy()) return;
    if (!validateAll(this.form)) return;
    this.submitting = true;
    this.busy.set(true);
    this.formError.set(null);
    const v = this.form.getRawValue();
    const base = {
      title: v.title.trim(), description: v.description.trim(), priority: v.priority, severity: v.severity,
      categoryId: v.categoryId || null, tags: parseTags(v.tags),
    };
    const editingTicket = this.existing.data();
    const req$ = this.editing() && editingTicket
      ? this.api.update(this.id() as string, { ...base, version: editingTicket.version })
      : this.api.create({ ...base, type: v.type, attachments: this.attachments() }, this.idempotencyKey);
    req$.subscribe({
      next: (ticket) => {
        this.toast.success(this.editing() ? 'Ticket updated' : `Ticket #${ticket.number} created`);
        this.finish();
        void this.router.navigate(['/tickets', ticket.id]);
      },
      error: (e: unknown) => {
        this.finish();
        if (isKind(e, 'version_conflict') && this.editing()) { this.conflict.set(true); return; }
        if (isKind(e, 'duplicate')) { this.formError.set('This ticket was already submitted. Check the ticket list before trying again.'); return; }
        if (!applyFieldErrors(e, this.form, FIELDS)) this.formError.set(describeError(e));
      },
    });
  }

  private finish(): void { this.submitting = false; this.busy.set(false); }
}
