import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQuery, keepPreviousData } from '@tanstack/react-query';
import { ticketApi, type TicketFilters } from '../api/endpoints';
import { describeError } from '../api/client';
import { PRIORITIES, SLA_STATUSES, STATUSES, TICKET_TYPES, type Ticket } from '../api/types';
import { useAuth, useTenantId } from '../auth/AuthContext';
import { isCustomerOnly } from '../auth/roles';
import { Button, Card, EmptyState, ErrorNotice, Input, Pagination, PriorityBadge, Select, Skeleton, SlaIndicator, StatusBadge, Table, type Column } from '../components/ui';
import { useAssignableUsers, useCategories } from '../hooks/useLookups';
import { formatDateTime, humanize } from '../lib/format';
import { filtersFromParams, nextSort, paramsFromFilters } from '../lib/ticketFilters';
import { qk } from '../lib/queryKeys';

export default function TicketListPage() {
  const { user } = useAuth();
  const t = useTenantId();
  const customer = isCustomerOnly(user);
  const [sp, setSp] = useSearchParams();
  const urlFilters = filtersFromParams(sp);
  // Customers only ever see their own tickets (server enforces too; this keeps the UI consistent).
  const filters: TicketFilters = customer ? { ...urlFilters, mine: true } : urlFilters;

  const [search, setSearch] = useState(urlFilters.q ?? '');
  useEffect(() => { setSearch(urlFilters.q ?? ''); }, [urlFilters.q]);

  // Debounced search -> URL (resets to page 0).
  useEffect(() => {
    if (search === (urlFilters.q ?? '')) return;
    const h = setTimeout(() => update({ q: search || undefined, page: 0 }), 300);
    return () => clearTimeout(h);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);

  function update(patch: Partial<TicketFilters>) {
    setSp(paramsFromFilters({ ...urlFilters, ...patch }), { replace: false });
  }
  const setFilter = (key: keyof TicketFilters) => (e: { target: { value: string } }) =>
    update({ [key]: e.target.value || undefined, page: 0 } as Partial<TicketFilters>);

  const query = useQuery({
    queryKey: qk.ticketList(t, filters),
    queryFn: () => ticketApi.list(filters),
    placeholderData: keepPreviousData,
  });
  const categories = useCategories();
  const users = useAssignableUsers();

  const columns: Column<Ticket>[] = [
    { key: 'number', header: '#', sortField: 'number', render: (r) => r.number },
    { key: 'title', header: 'Title', sortField: 'title', render: (r) => <Link to={`/tickets/${r.id}`}>{r.title}</Link> },
    { key: 'status', header: 'Status', sortField: 'status', render: (r) => <StatusBadge status={r.status} /> },
    { key: 'priority', header: 'Priority', sortField: 'priority', render: (r) => <PriorityBadge priority={r.priority} /> },
    { key: 'sla', header: 'SLA', render: (r) => <SlaIndicator status={r.slaStatus} due={r.resolutionDueAt} /> },
    ...(customer ? [] : [{ key: 'assignee', header: 'Assignee', render: (r: Ticket) => r.assignee?.displayName ?? <span className="muted">Unassigned</span> }]),
    { key: 'updated', header: 'Updated', sortField: 'updatedAt', render: (r) => formatDateTime(r.updatedAt) },
  ];

  const hasFilters = !!(urlFilters.q || urlFilters.status || urlFilters.priority || urlFilters.assigneeId || urlFilters.categoryId || urlFilters.type || urlFilters.slaStatus || (!customer && urlFilters.mine));

  return (
    <div>
      <div className="page-head">
        <h1>{customer ? 'My tickets' : 'Tickets'}</h1>
        <Link className="btn btn-primary btn-md" to="/tickets/new">New ticket</Link>
      </div>
      <Card>
        <form className="filters" role="search" aria-label="Filter tickets" onSubmit={(e) => e.preventDefault()}>
          <Input label="Search" type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Title or description" />
          <Select label="Status" value={urlFilters.status ?? ''} onChange={setFilter('status')}>
            <option value="">All</option>
            {STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
          </Select>
          <Select label="Priority" value={urlFilters.priority ?? ''} onChange={setFilter('priority')}>
            <option value="">All</option>
            {PRIORITIES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
          </Select>
          <Select label="Type" value={urlFilters.type ?? ''} onChange={setFilter('type')}>
            <option value="">All</option>
            {TICKET_TYPES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
          </Select>
          <Select label="Category" value={urlFilters.categoryId ?? ''} onChange={setFilter('categoryId')}>
            <option value="">All</option>
            {categories.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </Select>
          <Select label="SLA" value={urlFilters.slaStatus ?? ''} onChange={setFilter('slaStatus')}>
            <option value="">All</option>
            {SLA_STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
          </Select>
          {!customer && (
            <Select label="Assignee" value={urlFilters.assigneeId ?? ''} onChange={setFilter('assigneeId')}>
              <option value="">Anyone</option>
              {users.data?.map((u) => <option key={u.id} value={u.id}>{u.displayName}</option>)}
            </Select>
          )}
          {!customer && (
            <div className="field field-check">
              <label>
                <input type="checkbox" checked={!!urlFilters.mine} onChange={(e) => update({ mine: e.target.checked || undefined, page: 0 })} />
                {' '}Assigned to me
              </label>
            </div>
          )}
          {hasFilters && <Button variant="ghost" onClick={() => { setSearch(''); setSp(new URLSearchParams()); }}>Clear filters</Button>}
        </form>
      </Card>

      <Card className="mt">
        {query.isLoading && <Skeleton lines={8} label="Loading tickets" />}
        {query.isError && <ErrorNotice message={describeError(query.error)} onRetry={() => void query.refetch()} />}
        {query.data && query.data.content.length === 0 && (
          <EmptyState title="No tickets found" description={hasFilters ? 'Try changing or clearing the filters.' : 'Create your first ticket to get started.'}
            action={<Link className="btn btn-primary btn-md" to="/tickets/new">New ticket</Link>} />
        )}
        {query.data && query.data.content.length > 0 && (
          <div aria-busy={query.isFetching}>
            <Table caption="Tickets" columns={columns} rows={query.data.content} rowKey={(r) => r.id}
              sort={urlFilters.sort} onSort={(f) => update({ sort: nextSort(urlFilters.sort, f), page: 0 })} />
            <Pagination page={query.data.page} totalPages={query.data.totalPages} totalElements={query.data.totalElements} onPage={(p) => update({ page: p })} />
          </div>
        )}
      </Card>
    </div>
  );
}
