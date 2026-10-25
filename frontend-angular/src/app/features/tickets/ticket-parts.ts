import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';
import { TicketApi } from '../../core/endpoints';
import { describeError, isKind } from '../../core/errors';
import { buildCommentForm, buildTransitionForm } from '../../core/form-schemas';
import { formatDateTime, humanize } from '../../core/format';
import { loadable } from '../../core/loadable';
import { LookupsService } from '../../core/lookups.service';
import { DEFAULT_TRANSITIONS, Ticket, TicketStatus } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { validateAll } from '../../core/validators';
import { BadgeComponent, AiBadgeComponent } from '../../shared/ui/badges';
import { ButtonComponent } from '../../shared/ui/button';
import { CardComponent } from '../../shared/ui/card';
import { EmptyStateComponent, ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';
import { FieldComponent, FieldControlDirective } from '../../shared/ui/field';
import { ModalComponent } from '../../shared/ui/modal';
import { TabDef, TabsComponent } from '../../shared/ui/tabs';

/* ------------------------------------------------------------------ assignment */

@Component({
  selector: 'app-assign-card',
  imports: [CardComponent, ButtonComponent],
  template: `
    <app-card title="Assignment">
      <p class="small">Currently: <strong>{{ ticket().assignee?.displayName ?? 'Unassigned' }}</strong></p>
      <div class="field">
        <label for="assignee">Assignee</label>
        <select id="assignee" [disabled]="busy()" (change)="value.set($any($event.target).value)">
          <option value="" [selected]="value() === ''">Unassigned</option>
          @for (u of lookups.assignable(); track u.id) { <option [value]="u.id" [selected]="u.id === value()">{{ u.displayName }}</option> }
        </select>
      </div>
      <button appButton [disabled]="!dirty()" [loading]="busy()" (click)="save()">Save assignment</button>
    </app-card>
  `,
})
export class AssignCardComponent {
  readonly ticket = input.required<Ticket>();
  readonly updated = output<Ticket>();
  readonly conflict = output<void>();
  protected readonly lookups = inject(LookupsService);
  private readonly api = inject(TicketApi);
  private readonly toast = inject(ToastService);
  protected readonly value = signal('');
  protected readonly busy = signal(false);

  constructor() {
    this.lookups.ensureUsers();
    // re-sync the select when a newer ticket version arrives
    effect(() => { const id = this.ticket().assignee?.id ?? ''; untracked(() => this.value.set(id)); });
  }

  protected dirty(): boolean { return this.value() !== (this.ticket().assignee?.id ?? ''); }

  protected save(): void {
    const t = this.ticket();
    this.busy.set(true);
    this.api.assign(t.id, this.value() || null, t.version).subscribe({
      next: (u) => { this.busy.set(false); this.toast.success('Assignment updated'); this.updated.emit(u); },
      error: (e: unknown) => {
        this.busy.set(false);
        if (isKind(e, 'version_conflict')) this.conflict.emit(); else this.toast.error(describeError(e));
      },
    });
  }
}

/* ------------------------------------------------------------------ status transitions */

@Component({
  selector: 'app-transition-card',
  imports: [ReactiveFormsModule, CardComponent, ButtonComponent, ModalComponent, FieldComponent, FieldControlDirective],
  template: `
    <app-card title="Status">
      @if (allowed().length === 0) { <p class="muted">No transitions available.</p> }
      @else {
        <div class="btn-row" role="group" aria-label="Change status">
          @for (s of allowed(); track s) { <button appButton variant="secondary" size="sm" (click)="open(s)">{{ human(s) }}</button> }
        </div>
      }
      @if (target(); as t) {
        <app-modal [title]="'Move to ' + human(t)" (closed)="close()">
          <form [formGroup]="form" (ngSubmit)="confirm()" novalidate aria-label="Transition reason">
            <app-field label="Reason" [control]="form.controls.reason" required>
              <textarea appFieldControl formControlName="reason" rows="3" data-autofocus></textarea>
            </app-field>
            @if (serverError(); as m) { <p class="notice notice-error" role="alert">{{ m }}</p> }
            <div class="modal-footer">
              <button appButton variant="secondary" (click)="close()">Cancel</button>
              <button appButton type="submit" [loading]="busy()">Confirm</button>
            </div>
          </form>
        </app-modal>
      }
    </app-card>
  `,
})
export class TransitionCardComponent {
  readonly ticket = input.required<Ticket>();
  readonly updated = output<Ticket>();
  readonly conflict = output<void>();
  private readonly api = inject(TicketApi);
  private readonly toast = inject(ToastService);
  protected readonly human = humanize;
  protected readonly form = buildTransitionForm();
  protected readonly target = signal<TicketStatus | null>(null);
  protected readonly busy = signal(false);
  protected readonly serverError = signal<string | null>(null);

  protected allowed(): TicketStatus[] { return DEFAULT_TRANSITIONS[this.ticket().status] ?? []; }
  protected open(s: TicketStatus): void { this.target.set(s); }
  protected close(): void { this.target.set(null); this.serverError.set(null); this.form.reset(); }

  protected confirm(): void {
    const to = this.target();
    if (!to || this.busy() || !validateAll(this.form)) return;
    const t = this.ticket();
    this.busy.set(true);
    this.api.transition(t.id, to, this.form.controls.reason.value.trim(), t.version).subscribe({
      next: (u) => { this.busy.set(false); this.toast.success(`Status changed to ${humanize(u.status)}`); this.close(); this.updated.emit(u); },
      error: (e: unknown) => {
        this.busy.set(false);
        if (isKind(e, 'invalid_transition')) { this.serverError.set(e.message || 'That transition is not allowed.'); return; }
        this.close();
        if (isKind(e, 'version_conflict')) this.conflict.emit(); else this.toast.error(describeError(e));
      },
    });
  }
}

/* ------------------------------------------------------------------ comments */

@Component({
  selector: 'app-comments-card',
  imports: [ReactiveFormsModule, CardComponent, ButtonComponent, TabsComponent, BadgeComponent, AiBadgeComponent, SkeletonComponent, ErrorNoticeComponent, EmptyStateComponent, FieldComponent, FieldControlDirective],
  template: `
    <app-card title="Comments" [cardClass]="'comments comments-' + active().toLowerCase()">
      <app-tabs [tabs]="tabs()" [active]="active()" (activeChange)="tab.set($any($event))" idBase="comments" label="Comment visibility" />
      <div role="tabpanel" [id]="'comments-panel-' + active()" [attr.aria-labelledby]="'comments-tab-' + active()" tabindex="0">
        @if (active() === 'INTERNAL') {
          <p class="banner-internal" role="note">Internal notes are visible to staff only and never shown to customers.</p>
        } @else if (!customer()) {
          <p class="banner-public" role="note">Public replies are visible to the customer.</p>
        }
        @if (q.loading() && !q.data()) { <app-skeleton [lines]="3" label="Loading comments" /> }
        @if (q.error(); as e) { <app-error-notice [message]="describe(e)" retryable (retry)="q.load()" /> }
        @if (q.data() && shown().length === 0) { <app-empty-state [title]="active() === 'INTERNAL' ? 'No internal notes yet' : 'No replies yet'" /> }
        <ul class="comment-list">
          @for (c of shown(); track c.id) {
            <li [class]="'comment comment-' + c.visibility.toLowerCase()">
              <div class="comment-head">
                <strong>{{ c.authorName }}</strong>
                @if (c.visibility === 'INTERNAL') { <app-badge tone="internal">Internal</app-badge> }
                @if (c.aiGenerated) { <app-ai-badge>AI-generated, approved by agent</app-ai-badge> }
                <time [attr.datetime]="c.createdAt" class="muted small">{{ fmt(c.createdAt) }}</time>
              </div>
              <p>{{ c.body }}</p>
            </li>
          }
        </ul>
        <form [formGroup]="form" (ngSubmit)="post()" novalidate [attr.aria-label]="active() === 'INTERNAL' ? 'Add internal note' : 'Add public reply'">
          <app-field [label]="fieldLabel()" [control]="form.controls.body">
            <textarea appFieldControl formControlName="body" rows="3"></textarea>
          </app-field>
          <button appButton type="submit" [loading]="busy()">{{ active() === 'INTERNAL' ? 'Add internal note' : 'Post reply' }}</button>
        </form>
      </div>
    </app-card>
  `,
})
export class CommentsCardComponent {
  readonly ticketId = input.required<string>();
  /** bumped by the parent when comments changed elsewhere (e.g. an approved AI reply) */
  readonly refreshKey = input(0);
  readonly changed = output<void>();
  private readonly api = inject(TicketApi);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  protected readonly customer = this.auth.isCustomer;
  protected readonly tab = signal<'PUBLIC' | 'INTERNAL'>('PUBLIC');
  protected readonly q = loadable(() => this.api.comments(this.ticketId()));
  protected readonly form = buildCommentForm();
  protected readonly busy = signal(false);
  protected readonly describe = describeError;
  protected readonly fmt = formatDateTime;

  constructor() {
    effect(() => { this.ticketId(); this.refreshKey(); untracked(() => this.q.load()); });
  }

  protected active(): 'PUBLIC' | 'INTERNAL' { return this.customer() ? 'PUBLIC' : this.tab(); }
  protected shown() { return (this.q.data() ?? []).filter((c) => c.visibility === this.active()); }
  protected fieldLabel(): string { return this.active() === 'INTERNAL' ? 'Add internal note' : this.customer() ? 'Add a reply' : 'Add public reply'; }
  protected tabs(): TabDef[] {
    const all = this.q.data() ?? [];
    const n = (v: string) => all.filter((c) => c.visibility === v).length;
    return this.customer()
      ? [{ id: 'PUBLIC', label: 'Conversation', badge: this.shown().length }]
      : [{ id: 'PUBLIC', label: 'Public replies', badge: n('PUBLIC'), tone: 'public' }, { id: 'INTERNAL', label: 'Internal notes', badge: n('INTERNAL'), tone: 'internal' }];
  }

  protected post(): void {
    if (this.busy() || !validateAll(this.form)) return;
    this.busy.set(true);
    this.api.addComment(this.ticketId(), this.form.controls.body.value.trim(), this.customer() ? 'PUBLIC' : this.tab()).subscribe({
      next: () => {
        this.busy.set(false);
        this.form.reset();
        this.toast.success('Comment added');
        this.q.load();
        this.changed.emit();
      },
      error: (e: unknown) => { this.busy.set(false); this.toast.error(describeError(e)); },
    });
  }
}

/* ------------------------------------------------------------------ activity */

@Component({
  selector: 'app-activity-card',
  imports: [CardComponent, SkeletonComponent, ErrorNoticeComponent, EmptyStateComponent],
  template: `
    <app-card title="Activity">
      @if (q.loading() && !q.data()) { <app-skeleton [lines]="3" label="Loading activity" /> }
      @if (q.error(); as e) { <app-error-notice [message]="describe(e)" /> }
      @if (q.data()?.length === 0) { <app-empty-state title="No activity yet" /> }
      <ol class="timeline">
        @for (a of q.data(); track a.id) {
          <li>
            <span class="tl-dot" aria-hidden="true"></span>
            <div><strong>{{ human(a.type) }}</strong> by {{ a.actorName }}<br /><span class="muted small">{{ a.details }}</span><br />
              <time [attr.datetime]="a.at" class="muted small">{{ fmt(a.at) }}</time></div>
          </li>
        }
      </ol>
    </app-card>
  `,
})
export class ActivityCardComponent {
  readonly ticketId = input.required<string>();
  /** bumped by the parent after anything that appends activity (comment, assignment, transition, AI decision) */
  readonly refreshKey = input(0);
  private readonly api = inject(TicketApi);
  protected readonly q = loadable(() => this.api.activity(this.ticketId()));
  protected readonly describe = describeError;
  protected readonly human = humanize;
  protected readonly fmt = formatDateTime;
  constructor() {
    effect(() => { this.ticketId(); this.refreshKey(); untracked(() => this.q.load()); });
  }
}
