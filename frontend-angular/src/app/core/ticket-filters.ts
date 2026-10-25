import type { Params } from '@angular/router';
import type { TicketFilters } from './endpoints';

export const DEFAULT_PAGE_SIZE = 20;
export const DEFAULT_SORT = 'createdAt,desc';

const STRING_KEYS = ['q', 'status', 'priority', 'assigneeId', 'categoryId', 'type', 'slaStatus'] as const;

function first(v: unknown): string | null {
  if (Array.isArray(v)) return v.length ? String(v[0]) : null;
  return v === undefined || v === null ? null : String(v);
}

/** Router query params -> API filters (the URL is the single source of truth for list state). */
export function filtersFromParams(p: Params): TicketFilters {
  const f: TicketFilters = {
    page: Math.max(0, parseInt(first(p['page']) ?? '0', 10) || 0),
    size: Math.min(100, Math.max(1, parseInt(first(p['size']) ?? String(DEFAULT_PAGE_SIZE), 10) || DEFAULT_PAGE_SIZE)),
    sort: first(p['sort']) || DEFAULT_SORT,
  };
  for (const k of STRING_KEYS) {
    const v = first(p[k]);
    if (v) f[k] = v;
  }
  if (first(p['mine']) === 'true') f.mine = true;
  return f;
}

/** Filters -> router query params, omitting defaults so URLs stay short and shareable. */
export function paramsFromFilters(f: TicketFilters): Params {
  const out: Params = {};
  for (const k of STRING_KEYS) {
    const v = f[k];
    if (v) out[k] = v;
  }
  if (f.mine) out['mine'] = 'true';
  if (f.sort && f.sort !== DEFAULT_SORT) out['sort'] = f.sort;
  if (f.size && f.size !== DEFAULT_PAGE_SIZE) out['size'] = String(f.size);
  if (f.page && f.page > 0) out['page'] = String(f.page);
  return out;
}

/** Toggle sort: first click asc, second desc, new field starts asc. */
export function nextSort(current: string | undefined, field: string): string {
  const [f, d] = (current ?? '').split(',');
  if (f === field) return `${field},${d === 'asc' ? 'desc' : 'asc'}`;
  return `${field},asc`;
}
