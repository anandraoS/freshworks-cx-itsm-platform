import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { humanize } from '../../core/format';
import type { Priority, TicketStatus } from '../../core/models';

export type BadgeTone = 'neutral' | 'info' | 'success' | 'warn' | 'danger' | 'ai' | 'internal';

@Component({
  selector: 'app-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span [class]="'badge badge-' + tone()" [attr.title]="title() || null"><ng-content /></span>`,
})
export class BadgeComponent {
  readonly tone = input<BadgeTone>('neutral');
  readonly title = input('');
}

const PRIORITY_TONE: Record<Priority, BadgeTone> = { LOW: 'neutral', MEDIUM: 'info', HIGH: 'warn', URGENT: 'danger' };

@Component({
  selector: 'app-priority-badge',
  imports: [BadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-badge [tone]="tone()">{{ label() }}</app-badge>`,
})
export class PriorityBadgeComponent {
  readonly priority = input.required<Priority>();
  protected readonly tone = computed(() => PRIORITY_TONE[this.priority()] ?? 'neutral');
  protected readonly label = computed(() => humanize(this.priority()));
}

const STATUS_TONE: Record<TicketStatus, BadgeTone> = {
  NEW: 'info', OPEN: 'info', PENDING: 'warn', ON_HOLD: 'neutral', RESOLVED: 'success', CLOSED: 'neutral', REOPENED: 'danger',
};

@Component({
  selector: 'app-status-badge',
  imports: [BadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-badge [tone]="tone()">{{ label() }}</app-badge>`,
})
export class StatusBadgeComponent {
  readonly status = input.required<TicketStatus>();
  protected readonly tone = computed(() => STATUS_TONE[this.status()] ?? 'neutral');
  protected readonly label = computed(() => humanize(this.status()));
}

/** Marks AI-produced content; the text is always visible (not colour-only). */
@Component({
  selector: 'app-ai-badge',
  imports: [BadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-badge tone="ai"><span aria-hidden="true">✦ </span><ng-content>AI-generated</ng-content></app-badge>`,
})
export class AiBadgeComponent {}
