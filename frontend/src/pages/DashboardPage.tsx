import { useState } from 'react';
import { useQuery, keepPreviousData } from '@tanstack/react-query';
import { analyticsApi } from '../api/endpoints';
import { describeError } from '../api/client';
import { PRIORITIES } from '../api/types';
import { useTenantId } from '../auth/AuthContext';
import { BarChart, DonutChart, LineChart } from '../components/charts/Charts';
import { Card, EmptyState, ErrorNotice, Select, Skeleton } from '../components/ui';
import { formatMinutes, humanize } from '../lib/format';
import { qk } from '../lib/queryKeys';

const DAY_OPTIONS = [7, 14, 30, 90];
const PRIORITY_COLORS: Record<string, string> = { LOW: '#64748b', MEDIUM: '#2563eb', HIGH: '#d97706', URGENT: '#dc2626' };

function Kpi({ label, value, hint }: { label: string; value: string | number; hint?: string }) {
  return (
    <div className="kpi">
      <p className="kpi-label">{label}</p>
      <p className="kpi-value">{value}</p>
      {hint && <p className="muted small">{hint}</p>}
    </div>
  );
}

export default function DashboardPage() {
  const t = useTenantId();
  const [days, setDays] = useState(30);
  const q = useQuery({ queryKey: qk.dashboard(t, days), queryFn: () => analyticsApi.dashboard(days), placeholderData: keepPreviousData });
  const d = q.data;

  return (
    <div>
      <div className="page-head">
        <h1>Organization dashboard</h1>
        <Select label="Time range" value={days} onChange={(e) => setDays(Number(e.target.value))}>
          {DAY_OPTIONS.map((n) => <option key={n} value={n}>Last {n} days</option>)}
        </Select>
      </div>
      {q.isLoading && <Skeleton lines={10} label="Loading dashboard" />}
      {q.isError && <ErrorNotice message={describeError(q.error)} onRetry={() => void q.refetch()} />}
      {d && (
        <div aria-busy={q.isFetching}>
          <div className="kpi-row">
            <Kpi label="Open tickets" value={d.openTickets} hint={`Backlog ${d.backlog}`} />
            <Kpi label="SLA compliance" value={`${d.slaCompliancePercent.toFixed(1)}%`} />
            <Kpi label="Avg first response" value={formatMinutes(d.avgFirstResponseMinutes)} />
            <Kpi label="Avg resolution" value={formatMinutes(d.avgResolutionMinutes)} />
            <Kpi label="Reopened" value={d.reopenedTickets} />
            <Kpi label="AI acceptance rate" value={`${d.aiAcceptanceRatePercent.toFixed(0)}%`} hint="Approved AI replies" />
          </div>
          <div className="dash-grid">
            <Card title="Resolution trend">
              {d.resolutionTrend.length === 0 ? <EmptyState title="No data for this period" /> : (
                <LineChart title="Created vs resolved tickets" labels={d.resolutionTrend.map((p) => p.date)}
                  series={[{ name: 'Created', color: '#2563eb', points: d.resolutionTrend.map((p) => p.created) }, { name: 'Resolved', color: '#16a34a', points: d.resolutionTrend.map((p) => p.resolved) }]} />
              )}
            </Card>
            <Card title="Priority distribution">
              <DonutChart title="Tickets by priority" data={PRIORITIES.map((p) => ({ label: humanize(p), value: d.ticketsByPriority[p] ?? 0, color: PRIORITY_COLORS[p] }))} />
            </Card>
            <Card title="Agent workload">
              {d.agentWorkload.length === 0 ? <EmptyState title="No assigned open tickets" /> : (
                <BarChart title="Open tickets per agent" data={d.agentWorkload.map((a) => ({ label: a.name, value: a.openCount }))} />
              )}
            </Card>
            <Card title="Tenant activity (last 7 days)">
              <dl className="meta meta-col">
                <div><dt>Tickets created</dt><dd>{d.tenantActivity.ticketsCreatedLast7d}</dd></div>
                <div><dt>Comments</dt><dd>{d.tenantActivity.commentsLast7d}</dd></div>
                <div><dt>Active users</dt><dd>{d.tenantActivity.activeUsers}</dd></div>
              </dl>
            </Card>
          </div>
        </div>
      )}
    </div>
  );
}
