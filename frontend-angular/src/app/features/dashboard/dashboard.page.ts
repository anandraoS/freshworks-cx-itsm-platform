import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { AnalyticsApi } from '../../core/endpoints';
import { describeError } from '../../core/errors';
import { formatMinutes, humanize } from '../../core/format';
import { loadable } from '../../core/loadable';
import { PRIORITIES } from '../../core/models';
import { BarChartComponent, DonutChartComponent, LineChartComponent, Series } from '../../shared/charts/charts';
import { CardComponent } from '../../shared/ui/card';
import { EmptyStateComponent, ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';

const DAY_OPTIONS = [7, 14, 30, 90];
const PRIORITY_COLORS: Record<string, string> = { LOW: '#64748b', MEDIUM: '#2563eb', HIGH: '#d97706', URGENT: '#dc2626' };

@Component({
  selector: 'app-dashboard-page',
  imports: [CardComponent, SkeletonComponent, ErrorNoticeComponent, EmptyStateComponent, BarChartComponent, DonutChartComponent, LineChartComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-head">
      <h1>Organization dashboard</h1>
      <div class="field">
        <label for="days">Time range</label>
        <select id="days" [value]="days()" (change)="setDays($any($event.target).value)">
          @for (n of dayOptions; track n) { <option [value]="n" [selected]="n === days()">Last {{ n }} days</option> }
        </select>
      </div>
    </div>
    @if (q.loading() && !q.data()) { <app-skeleton [lines]="10" label="Loading dashboard" /> }
    @if (q.error(); as e) { <app-error-notice [message]="describe(e)" retryable (retry)="q.load()" /> }
    @if (q.data(); as d) {
      <div [attr.aria-busy]="q.loading()">
        <div class="kpi-row">
          <div class="kpi"><p class="kpi-label">Open tickets</p><p class="kpi-value">{{ d.openTickets }}</p><p class="muted small">Backlog {{ d.backlog }}</p></div>
          <div class="kpi"><p class="kpi-label">SLA compliance</p><p class="kpi-value">{{ d.slaCompliancePercent.toFixed(1) }}%</p></div>
          <div class="kpi"><p class="kpi-label">Avg first response</p><p class="kpi-value">{{ mins(d.avgFirstResponseMinutes) }}</p></div>
          <div class="kpi"><p class="kpi-label">Avg resolution</p><p class="kpi-value">{{ mins(d.avgResolutionMinutes) }}</p></div>
          <div class="kpi"><p class="kpi-label">Reopened</p><p class="kpi-value">{{ d.reopenedTickets }}</p></div>
          <div class="kpi"><p class="kpi-label">AI acceptance rate</p><p class="kpi-value">{{ d.aiAcceptanceRatePercent.toFixed(0) }}%</p><p class="muted small">Approved AI replies</p></div>
        </div>
        <div class="dash-grid">
          <app-card title="Resolution trend">
            @if (d.resolutionTrend.length === 0) { <app-empty-state title="No data for this period" /> }
            @else { <app-line-chart title="Created vs resolved tickets" [labels]="trendLabels()" [series]="trendSeries()" /> }
          </app-card>
          <app-card title="Priority distribution">
            <app-donut-chart title="Tickets by priority" [data]="priorityData()" />
          </app-card>
          <app-card title="Agent workload">
            @if (d.agentWorkload.length === 0) { <app-empty-state title="No assigned open tickets" /> }
            @else { <app-bar-chart title="Open tickets per agent" [data]="workloadData()" /> }
          </app-card>
          <app-card title="Tenant activity (last 7 days)">
            <dl class="meta meta-col">
              <div><dt>Tickets created</dt><dd>{{ d.tenantActivity.ticketsCreatedLast7d }}</dd></div>
              <div><dt>Comments</dt><dd>{{ d.tenantActivity.commentsLast7d }}</dd></div>
              <div><dt>Active users</dt><dd>{{ d.tenantActivity.activeUsers }}</dd></div>
            </dl>
          </app-card>
        </div>
      </div>
    }
  `,
})
export class DashboardPage implements OnInit {
  private readonly api = inject(AnalyticsApi);
  protected readonly dayOptions = DAY_OPTIONS;
  protected readonly days = signal(30);
  protected readonly q = loadable(() => this.api.dashboard(this.days()));
  protected readonly describe = describeError;
  protected readonly mins = formatMinutes;

  protected readonly trendLabels = computed(() => this.q.data()?.resolutionTrend.map((p) => p.date) ?? []);
  protected readonly trendSeries = computed<Series[]>(() => {
    const t = this.q.data()?.resolutionTrend ?? [];
    return [
      { name: 'Created', color: '#2563eb', points: t.map((p) => p.created) },
      { name: 'Resolved', color: '#16a34a', points: t.map((p) => p.resolved) },
    ];
  });
  protected readonly priorityData = computed(() =>
    PRIORITIES.map((p) => ({ label: humanize(p), value: this.q.data()?.ticketsByPriority[p] ?? 0, color: PRIORITY_COLORS[p] })));
  protected readonly workloadData = computed(() => (this.q.data()?.agentWorkload ?? []).map((a) => ({ label: a.name, value: a.openCount })));

  ngOnInit(): void { this.q.load(); }

  protected setDays(v: string): void {
    this.days.set(Number(v));
    this.q.load();
  }
}
