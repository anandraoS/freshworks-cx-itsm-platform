export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

export function formatMinutes(min: number | null | undefined): string {
  if (min === null || min === undefined || Number.isNaN(min)) return '—';
  if (min < 60) return `${Math.round(min)}m`;
  const h = min / 60;
  if (h < 48) return `${h.toFixed(1)}h`;
  return `${(h / 24).toFixed(1)}d`;
}

/** Human countdown to a deadline; negative => overdue. */
export function formatCountdown(dueIso: string | null | undefined, now: number = Date.now()): { text: string; overdue: boolean } {
  if (!dueIso) return { text: '—', overdue: false };
  const diff = new Date(dueIso).getTime() - now;
  if (Number.isNaN(diff)) return { text: '—', overdue: false };
  const abs = Math.abs(diff);
  const mins = Math.floor(abs / 60000);
  const text = mins < 60 ? `${mins}m` : mins < 60 * 48 ? `${Math.floor(mins / 60)}h ${mins % 60}m` : `${Math.floor(mins / 1440)}d`;
  return diff < 0 ? { text: `${text} overdue`, overdue: true } : { text: `${text} left`, overdue: false };
}

export function humanize(s: string): string {
  return s.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase());
}

export function authorName(a: Article_Author): string {
  if (!a) return '—';
  return typeof a === 'string' ? a : a.displayName;
}
type Article_Author = string | { id: string; displayName: string } | null;
