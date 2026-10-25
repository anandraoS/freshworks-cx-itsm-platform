import { DEFAULT_SORT, filtersFromParams, nextSort, paramsFromFilters } from './ticket-filters';

describe('ticket filters <-> URL', () => {
  it('applies defaults for an empty query string', () => {
    expect(filtersFromParams({})).toEqual({ page: 0, size: 20, sort: DEFAULT_SORT });
  });

  it('reads filters, clamps page/size and ignores junk', () => {
    const f = filtersFromParams({ q: 'vpn', status: 'OPEN', priority: 'HIGH', page: '-4', size: '9999', mine: 'true', unknown: 'x' });
    expect(f).toEqual({ q: 'vpn', status: 'OPEN', priority: 'HIGH', page: 0, size: 100, sort: DEFAULT_SORT, mine: true });
    expect(filtersFromParams({ page: 'abc', size: '0' }).size).toBe(20);
  });

  it('writes only non-default values (short, shareable URLs)', () => {
    expect(paramsFromFilters({ page: 0, size: 20, sort: DEFAULT_SORT })).toEqual({});
    expect(paramsFromFilters({ q: 'a', status: 'OPEN', page: 2, size: 50, sort: 'title,asc', mine: true }))
      .toEqual({ q: 'a', status: 'OPEN', mine: 'true', sort: 'title,asc', size: '50', page: '2' });
  });

  it('round-trips', () => {
    const f = { q: 'printer', slaStatus: 'BREACHED', categoryId: 'c1', page: 3, size: 20, sort: 'priority,desc' };
    expect(filtersFromParams(paramsFromFilters(f))).toEqual(f);
  });

  it('toggles sort direction and resets on a new field', () => {
    expect(nextSort(undefined, 'title')).toBe('title,asc');
    expect(nextSort('title,asc', 'title')).toBe('title,desc');
    expect(nextSort('title,desc', 'title')).toBe('title,asc');
    expect(nextSort('title,desc', 'status')).toBe('status,asc');
  });
});
