import type { TicketFilters } from '../api/endpoints';

export const DEFAULT_PAGE_SIZE = 20;
export const DEFAULT_SORT = 'createdAt,desc';

const STRING_KEYS = ['q', 'status', 'priority', 'assigneeId', 'categoryId', 'type', 'slaStatus'] as const;

/** URL search params -> API filters (the URL is the single source of truth for list state). */
export function filtersFromParams(sp: URLSearchParams): TicketFilters {
  const f: TicketFilters = {
    page: Math.max(0, parseInt(sp.get('page') ?? '0', 10) || 0),
    size: Math.min(100, Math.max(1, parseInt(sp.get('size') ?? String(DEFAULT_PAGE_SIZE), 10) || DEFAULT_PAGE_SIZE)),
    sort: sp.get('sort') || DEFAULT_SORT,
  };
  for (const k of STRING_KEYS) {
    const v = sp.get(k);
    if (v) f[k] = v;
  }
  if (sp.get('mine') === 'true') f.mine = true;
  return f;
}

/** Filters -> URL params, omitting defaults so URLs stay short and shareable. */
export function paramsFromFilters(f: TicketFilters): URLSearchParams {
  const sp = new URLSearchParams();
  for (const k of STRING_KEYS) {
    const v = f[k];
    if (v) sp.set(k, v);
  }
  if (f.mine) sp.set('mine', 'true');
  if (f.sort && f.sort !== DEFAULT_SORT) sp.set('sort', f.sort);
  if (f.size && f.size !== DEFAULT_PAGE_SIZE) sp.set('size', String(f.size));
  if (f.page && f.page > 0) sp.set('page', String(f.page));
  return sp;
}

/** Toggle sort: first click asc, second desc, on a new field start asc. */
export function nextSort(current: string | undefined, field: string): string {
  const [f, d] = (current ?? '').split(',');
  if (f === field) return `${field},${d === 'asc' ? 'desc' : 'asc'}`;
  return `${field},asc`;
}
