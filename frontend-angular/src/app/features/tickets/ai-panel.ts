import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AiApi } from '../../core/endpoints';
import { describeError, isKind } from '../../core/errors';
import { loadable } from '../../core/loadable';
import type { AiSuggestion } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { AiBadgeComponent } from '../../shared/ui/badges';
import { ButtonComponent } from '../../shared/ui/button';
import { CardComponent } from '../../shared/ui/card';
import { ConfirmDialogComponent } from '../../shared/ui/confirm-dialog';
import { ConfidenceMeterComponent, ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';

export const AI_LABEL = 'AI-generated (mock rule-based provider)';
export const DEFAULT_DEGRADED = 'AI assistance is temporarily unavailable. You can continue working on the ticket manually.';

/**
 * AI assist panel. Nothing is ever sent to the customer by the AI: the reply is only a draft that an agent must
 * edit/approve (approve posts it as a PUBLIC comment) or reject. Provider failure shows a degraded fallback.
 */
@Component({
  selector: 'app-ai-panel',
  imports: [RouterLink, CardComponent, ButtonComponent, AiBadgeComponent, ConfidenceMeterComponent, ErrorNoticeComponent, SkeletonComponent, ConfirmDialogComponent],
  template: `
    <app-card title="AI assist" cardClass="ai-panel">
      <app-ai-badge cardTitleExtra />
      <p class="muted small">{{ label }}. Suggestions are drafts only: nothing is sent to the customer unless you approve it.</p>

      @if (error(); as m) { <app-error-notice [message]="m" /> }

      @if (!result() && !unavailable() && !analyzing()) {
        <button appButton (click)="analyze()">Analyze ticket</button>
      }

      @if (analyzing()) {
        <div aria-live="polite"><p>Analyzing…</p><app-skeleton [lines]="4" label="AI analysis in progress" /></div>
      }

      @if (degraded()) {
        <div class="notice notice-warn" role="status" data-testid="ai-degraded">
          <span>{{ result()?.fallbackMessage || defaultDegraded }}</span>
          <button appButton size="sm" variant="secondary" [loading]="analyzing()" (click)="retry()">Try again</button>
        </div>
      }

      @if (result(); as r) {
        @if (!r.degraded) {
          <div class="ai-body" data-testid="ai-result">
            <section aria-labelledby="ai-summary-h"><h3 id="ai-summary-h">Summary</h3><p>{{ r.summary }}</p></section>
            <section aria-label="Confidence"><h3>Confidence</h3><app-confidence-meter [value]="r.confidence" /></section>
            <section aria-labelledby="ai-expl-h"><h3 id="ai-expl-h">Why this suggestion</h3><p>{{ r.explanation }}</p></section>
            @if (r.suggestedCategory || r.suggestedPriority) {
              <p class="small">Suggested category: <strong>{{ r.suggestedCategory?.name ?? '—' }}</strong> · Suggested priority: <strong>{{ r.suggestedPriority ?? '—' }}</strong> (informational; not applied automatically)</p>
            }
            @if (r.recommendedArticles.length > 0) {
              <section aria-labelledby="ai-art-h">
                <h3 id="ai-art-h">Recommended articles</h3>
                <ul>
                  @for (a of r.recommendedArticles; track a.id) {
                    <li><a [routerLink]="['/kb', a.id]">{{ a.title }}</a> <span class="muted small">({{ pct(a.score) }}% match)</span></li>
                  }
                </ul>
              </section>
            }

            <section aria-labelledby="ai-reply-h" class="ai-reply">
              <h3 id="ai-reply-h">Suggested reply <app-ai-badge>{{ label }}</app-ai-badge></h3>
              @if (r.status !== 'PENDING_REVIEW') {
                <p class="notice notice-info" role="status">{{ decisionText(r) }}</p>
                @if (r.suggestedReply) { <blockquote class="ai-quote">{{ r.suggestedReply }}</blockquote> }
              } @else if (r.suggestedReply) {
                <div class="field">
                  <label for="ai-reply-edit">Edit before approving</label>
                  <textarea id="ai-reply-edit" rows="6" [value]="reply()" [disabled]="busy()" (input)="reply.set($any($event.target).value)"></textarea>
                </div>
                <div class="form-actions">
                  <button appButton [disabled]="busy() || reply().trim().length === 0" (click)="confirmOpen.set(true)">Approve &amp; post reply</button>
                  <button appButton variant="secondary" [disabled]="busy()" (click)="rejecting.set(!rejecting())">Reject</button>
                  <button appButton variant="ghost" [disabled]="busy()" (click)="reanalyze()">Re-analyze</button>
                </div>
              } @else { <p class="muted">No reply was suggested.</p> }
            </section>

            @if (rejecting() && r.status === 'PENDING_REVIEW') {
              <div class="reject-box">
                <div class="field">
                  <label for="ai-reject-reason">Reason for rejecting</label>
                  <textarea id="ai-reject-reason" rows="2" [value]="reason()" (input)="reason.set($any($event.target).value)"></textarea>
                </div>
                <button appButton variant="danger" [loading]="rejectBusy()" [disabled]="reason().trim().length < 3" (click)="reject()">Confirm reject</button>
              </div>
            }
          </div>
        }
      }

      @if (confirmOpen()) {
        <app-confirm-dialog title="Post this reply to the customer?" confirmLabel="Approve & post" [busy]="approveBusy()" (confirmed)="approve()" (cancelled)="confirmOpen.set(false)">
          <p>The reply below will be posted as a <strong>public</strong> comment, visible to the customer. It is AI-generated; please make sure it is correct.</p>
        </app-confirm-dialog>
      }
    </app-card>
  `,
})
export class AiPanelComponent {
  readonly ticketId = input.required<string>();
  /** emitted after approve/reject so the parent can refresh comments + activity */
  readonly decided = output<void>();

  private readonly api = inject(AiApi);
  private readonly toast = inject(ToastService);
  protected readonly label = AI_LABEL;
  protected readonly defaultDegraded = DEFAULT_DEGRADED;

  protected readonly result = signal<AiSuggestion | null>(null);
  protected readonly unavailable = signal(false);
  protected readonly analyzing = signal(false);
  protected readonly approveBusy = signal(false);
  protected readonly rejectBusy = signal(false);
  protected readonly reply = signal('');
  protected readonly confirmOpen = signal(false);
  protected readonly rejecting = signal(false);
  protected readonly reason = signal('');
  protected readonly error = signal<string | null>(null);

  private readonly history = loadable(() => this.api.suggestions(this.ticketId()), (list) => {
    // show the latest pending suggestion when the user has not just analyzed
    if (this.result()) return;
    const pending = list.find((s) => s.status === 'PENDING_REVIEW' && !s.degraded);
    if (pending) { this.result.set(pending); this.reply.set(pending.suggestedReply ?? ''); }
  });

  constructor() {
    effect(() => { this.ticketId(); untracked(() => this.history.load()); });
  }

  protected busy(): boolean { return this.approveBusy() || this.rejectBusy(); }
  protected degraded(): boolean { return this.unavailable() || (this.result()?.degraded ?? false); }
  protected pct(score: number): number { return Math.round(score * 100); }
  protected decisionText(r: AiSuggestion): string {
    return r.status === 'REJECTED' ? 'This suggestion was rejected.' : r.status === 'EDITED_APPROVED' ? 'Approved after editing; reply posted.' : 'Approved; reply posted.';
  }

  protected analyze(): void {
    this.error.set(null);
    this.unavailable.set(false);
    this.analyzing.set(true);
    this.api.analyze(this.ticketId()).subscribe({
      next: (s) => { this.analyzing.set(false); this.result.set(s); this.reply.set(s.suggestedReply ?? ''); this.history.load(); },
      error: (e: unknown) => {
        this.analyzing.set(false);
        if (isKind(e, 'ai_unavailable')) { this.unavailable.set(true); this.result.set(null); } else this.error.set(describeError(e));
      },
    });
  }
  protected retry(): void { this.result.set(null); this.unavailable.set(false); this.analyze(); }
  protected reanalyze(): void { this.result.set(null); this.analyze(); }

  protected approve(): void {
    const cur = this.result();
    if (!cur || this.approveBusy()) return;
    const edited = this.reply().trim() !== (cur.suggestedReply ?? '').trim();
    this.approveBusy.set(true);
    this.api.approve(cur.suggestionId, edited ? this.reply().trim() : undefined).subscribe({
      next: (s) => { this.approveBusy.set(false); this.confirmOpen.set(false); this.afterDecision({ ...cur, ...s }, 'Reply approved and posted'); },
      error: (e: unknown) => { this.approveBusy.set(false); this.confirmOpen.set(false); this.error.set(describeError(e)); },
    });
  }

  protected reject(): void {
    const cur = this.result();
    if (!cur || this.rejectBusy()) return;
    this.rejectBusy.set(true);
    this.api.reject(cur.suggestionId, this.reason().trim()).subscribe({
      next: (s) => { this.rejectBusy.set(false); this.rejecting.set(false); this.reason.set(''); this.afterDecision({ ...cur, ...s, status: 'REJECTED' }, 'Suggestion rejected'); },
      error: (e: unknown) => { this.rejectBusy.set(false); this.error.set(describeError(e)); },
    });
  }

  private afterDecision(s: AiSuggestion, msg: string): void {
    this.result.set(s);
    this.toast.success(msg);
    this.decided.emit();
    this.history.load();
  }
}
