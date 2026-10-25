import { useEffect, useState, type ReactNode } from 'react';
import type { Priority, SlaStatus, TicketStatus } from '../../api/types';
import { formatCountdown, humanize } from '../../lib/format';

export function Badge({ tone = 'neutral', children, title }: { tone?: 'neutral' | 'info' | 'success' | 'warn' | 'danger' | 'ai' | 'internal'; children: ReactNode; title?: string }) {
  return <span className={`badge badge-${tone}`} title={title}>{children}</span>;
}

export function Card({ title, actions, children, className = '', as: Tag = 'section' }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; as?: 'section' | 'div' }) {
  return (
    <Tag className={`card ${className}`.trim()}>
      {(title || actions) && (
        <header className="card-head">
          {title && <h2 className="card-title">{title}</h2>}
          {actions && <div className="card-actions">{actions}</div>}
        </header>
      )}
      {children}
    </Tag>
  );
}

export function Skeleton({ lines = 3, height = 14, label = 'Loading' }: { lines?: number; height?: number; label?: string }) {
  return (
    <div className="skeleton-wrap" role="status" aria-label={label} aria-busy="true">
      {Array.from({ length: lines }, (_, i) => (
        <div key={i} className="skeleton" style={{ height, width: `${100 - (i % 3) * 12}%` }} />
      ))}
    </div>
  );
}

export function EmptyState({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return (
    <div className="empty" role="status">
      <p className="empty-title">{title}</p>
      {description && <p className="empty-desc">{description}</p>}
      {action}
    </div>
  );
}

export function ErrorNotice({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="notice notice-error" role="alert">
      <span>{message}</span>
      {onRetry && <button type="button" className="btn btn-secondary btn-sm" onClick={onRetry}>Retry</button>}
    </div>
  );
}

const PRIORITY_TONE: Record<Priority, 'neutral' | 'info' | 'warn' | 'danger'> = { LOW: 'neutral', MEDIUM: 'info', HIGH: 'warn', URGENT: 'danger' };
export function PriorityBadge({ priority }: { priority: Priority }) {
  return <Badge tone={PRIORITY_TONE[priority]}>{humanize(priority)}</Badge>;
}

const STATUS_TONE: Record<TicketStatus, 'neutral' | 'info' | 'success' | 'warn' | 'danger'> = {
  NEW: 'info', OPEN: 'info', PENDING: 'warn', ON_HOLD: 'neutral', RESOLVED: 'success', CLOSED: 'neutral', REOPENED: 'danger',
};
export function StatusBadge({ status }: { status: TicketStatus }) {
  return <Badge tone={STATUS_TONE[status]}>{humanize(status)}</Badge>;
}

export function AiBadge({ children = 'AI-generated' }: { children?: ReactNode }) {
  return <Badge tone="ai"><span aria-hidden="true">✦ </span>{children}</Badge>;
}

const SLA_TONE: Record<SlaStatus, 'success' | 'warn' | 'danger' | 'neutral' | 'info'> = {
  ON_TRACK: 'success', AT_RISK: 'warn', BREACHED: 'danger', MET: 'info', PAUSED: 'neutral',
};
/** SLA status badge; with `due` it also shows a live countdown (ticks every 30s). */
export function SlaIndicator({ status, due }: { status: SlaStatus; due?: string | null }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!due) return;
    const t = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(t);
  }, [due]);
  const cd = due && status !== 'MET' && status !== 'PAUSED' ? formatCountdown(due, now) : null;
  return (
    <span className="sla">
      <Badge tone={SLA_TONE[status]}>{humanize(status)}</Badge>
      {cd && <span className={`sla-count${cd.overdue ? ' overdue' : ''}`}>{cd.text}</span>}
    </span>
  );
}

export function ConfidenceMeter({ value }: { value: number }) {
  const pct = Math.max(0, Math.min(100, Math.round(value * 100)));
  return (
    <div className="meter" role="meter" aria-label="AI confidence" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-valuetext={`${pct} percent`}>
      <div className="meter-bar"><div className="meter-fill" style={{ width: `${pct}%` }} /></div>
      <span className="meter-label">{pct}%</span>
    </div>
  );
}
