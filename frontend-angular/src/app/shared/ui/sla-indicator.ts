import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, input, NgZone, signal } from '@angular/core';
import { formatCountdown, humanize } from '../../core/format';
import type { SlaStatus } from '../../core/models';
import { BadgeComponent, BadgeTone } from './badges';

const SLA_TONE: Record<SlaStatus, BadgeTone> = { ON_TRACK: 'success', AT_RISK: 'warn', BREACHED: 'danger', MET: 'info', PAUSED: 'neutral' };

/** SLA status badge; with `due` it also shows a live countdown that ticks every 30 s (timer cleaned up on destroy). */
@Component({
  selector: 'app-sla-indicator',
  imports: [BadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="sla">
      <app-badge [tone]="tone()">{{ label() }}</app-badge>
      @if (countdown(); as cd) { <span [class]="'sla-count' + (cd.overdue ? ' overdue' : '')">{{ cd.text }}</span> }
    </span>
  `,
})
export class SlaIndicatorComponent {
  readonly status = input.required<SlaStatus>();
  readonly due = input<string | null | undefined>(null);
  private readonly now = signal(Date.now());

  protected readonly tone = computed(() => SLA_TONE[this.status()] ?? 'neutral');
  protected readonly label = computed(() => humanize(this.status()));
  protected readonly countdown = computed(() => {
    const due = this.due();
    const s = this.status();
    return due && s !== 'MET' && s !== 'PAUSED' ? formatCountdown(due, this.now()) : null;
  });

  constructor() {
    // Outside the Angular zone: a periodic timer must not keep the app "unstable" (SSR/tests/whenStable); the signal
    // write still schedules change detection.
    const t = inject(NgZone).runOutsideAngular(() => setInterval(() => this.now.set(Date.now()), 30_000));
    inject(DestroyRef).onDestroy(() => clearInterval(t));
  }
}
