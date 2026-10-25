import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import TicketListPage from '../pages/TicketListPage';
import { filtersFromParams, nextSort, paramsFromFilters } from '../lib/ticketFilters';
import { makeUser, renderApp } from './utils';
import type { Ticket } from '../api/types';

vi.mock('../api/endpoints', () => ({
  ticketApi: { list: vi.fn() },
  categoryApi: { list: vi.fn().mockResolvedValue([{ id: 'c1', name: 'Hardware', type: 'SUPPORT', defaultPriority: 'LOW' }]) },
  userApi: { list: vi.fn().mockResolvedValue([{ id: 'u9', displayName: 'Agent Nine', email: 'a@x.test', roles: ['AGENT'], active: true }]) },
}));
import { ticketApi } from '../api/endpoints';

const ticket = (over: Partial<Ticket> = {}): Ticket => ({
  id: 't1', number: 7, type: 'SUPPORT', title: 'Laptop will not boot', description: 'd', status: 'OPEN', priority: 'HIGH', severity: 'MAJOR',
  categoryId: null, categoryName: null, tags: [], requester: { id: 'r', displayName: 'Rita' }, assignee: null,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-02T00:00:00Z', firstResponseDueAt: null, resolutionDueAt: null, firstRespondedAt: null, resolvedAt: null,
  slaStatus: 'AT_RISK', escalationLevel: 0, reopenCount: 0, version: 1, attachments: [], ...over,
});
const page = (content: Ticket[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });
const list = ticketApi.list as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => { list.mockReset(); list.mockResolvedValue(page([ticket()])); });

describe('ticket filter <-> query params mapping', () => {
  it('parses URL into API filters with defaults', () => {
    expect(filtersFromParams(new URLSearchParams('status=OPEN&page=2&mine=true&q=abc'))).toEqual({ page: 2, size: 20, sort: 'createdAt,desc', status: 'OPEN', q: 'abc', mine: true });
  });
  it('omits defaults when serializing, and round-trips', () => {
    const f = { page: 0, size: 20, sort: 'createdAt,desc', priority: 'URGENT' };
    expect(paramsFromFilters(f).toString()).toBe('priority=URGENT');
    expect(filtersFromParams(paramsFromFilters({ ...f, page: 3, sort: 'title,asc' }))).toMatchObject({ page: 3, sort: 'title,asc', priority: 'URGENT' });
  });
  it('clamps bad page/size input', () => {
    expect(filtersFromParams(new URLSearchParams('page=-4&size=9999'))).toMatchObject({ page: 0, size: 100 });
    expect(filtersFromParams(new URLSearchParams('page=abc&size=0'))).toMatchObject({ page: 0 });
  });
  it('nextSort toggles direction', () => {
    expect(nextSort('createdAt,desc', 'title')).toBe('title,asc');
    expect(nextSort('title,asc', 'title')).toBe('title,desc');
  });
});

describe('TicketListPage', () => {
  it('sends URL filters to the server and renders rows with SLA indicator', async () => {
    renderApp(<TicketListPage />, { route: '/tickets?status=OPEN&page=1', user: makeUser(['AGENT']) });
    expect(await screen.findByRole('link', { name: 'Laptop will not boot' })).toBeInTheDocument();
    expect(list).toHaveBeenCalledWith(expect.objectContaining({ status: 'OPEN', page: 1, size: 20, sort: 'createdAt,desc' }));
    expect(within(screen.getByRole('table')).getByText('At risk')).toBeInTheDocument();
  });

  it('changing a filter re-queries with new param, keeps others and resets page', async () => {
    renderApp(<TicketListPage />, { route: '/tickets?status=OPEN&page=1', user: makeUser(['AGENT']) });
    await screen.findByRole('link', { name: 'Laptop will not boot' });
    await userEvent.selectOptions(screen.getByLabelText('Priority'), 'HIGH');
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'OPEN', priority: 'HIGH', page: 0 })));
  });

  it('debounced search goes to the server as q', async () => {
    renderApp(<TicketListPage />, { route: '/tickets', user: makeUser(['AGENT']) });
    await screen.findByRole('link', { name: 'Laptop will not boot' });
    await userEvent.type(screen.getByLabelText('Search'), 'vpn');
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'vpn' })), { timeout: 2000 });
  });

  it('clicking a sortable header sends sort param', async () => {
    renderApp(<TicketListPage />, { route: '/tickets', user: makeUser(['AGENT']) });
    await screen.findByRole('link', { name: 'Laptop will not boot' });
    await userEvent.click(screen.getByRole('button', { name: /^Title/ }));
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'title,asc' })));
  });

  it('customers always query mine=true and do not see the assignee filter', async () => {
    renderApp(<TicketListPage />, { route: '/tickets', user: makeUser(['CUSTOMER']) });
    await screen.findByRole('link', { name: 'Laptop will not boot' });
    expect(list).toHaveBeenCalledWith(expect.objectContaining({ mine: true }));
    expect(screen.queryByLabelText('Assignee')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'My tickets' })).toBeInTheDocument();
  });

  it('shows an empty state and an error state with retry', async () => {
    list.mockResolvedValueOnce(page([]));
    const { unmount } = renderApp(<TicketListPage />, { route: '/tickets?q=zzz', user: makeUser(['AGENT']) });
    expect(await screen.findByText('No tickets found')).toBeInTheDocument();
    unmount();
    list.mockRejectedValueOnce(new Error('x'));
    renderApp(<TicketListPage />, { route: '/tickets', user: makeUser(['AGENT']) });
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('link', { name: 'Laptop will not boot' })).toBeInTheDocument();
  });
});
