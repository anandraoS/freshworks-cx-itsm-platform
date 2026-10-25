import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import TicketFormPage from '../pages/TicketFormPage';
import { ApiError } from '../api/client';
import { makeUser, renderApp } from './utils';

vi.mock('../api/endpoints', () => ({
  ticketApi: { create: vi.fn(), get: vi.fn(), update: vi.fn() },
  categoryApi: { list: vi.fn().mockResolvedValue([]) },
}));
import { ticketApi } from '../api/endpoints';
const create = ticketApi.create as unknown as ReturnType<typeof vi.fn>;

const created = { id: 'new-1', number: 42 };
function setup() {
  return renderApp(
    <Routes><Route path="/tickets/new" element={<TicketFormPage />} /><Route path="/tickets/:id" element={<p>detail page</p>} /></Routes>,
    { route: '/tickets/new', user: makeUser(['CUSTOMER']) },
  );
}
async function fillValid() {
  await userEvent.type(screen.getByLabelText(/^Title/), 'Cannot log in to portal');
  await userEvent.type(screen.getByLabelText(/^Description/), 'I get an error when I try to log in.');
}
beforeEach(() => create.mockReset());

describe('TicketFormPage', () => {
  it('shows validation errors and does not call the API', async () => {
    setup();
    await userEvent.click(screen.getByRole('button', { name: 'Create ticket' }));
    expect(await screen.findByText('Title must be at least 5 characters')).toBeInTheDocument();
    expect(create).not.toHaveBeenCalled();
  });

  it('duplicate-submit protection: double click sends one request and disables the button while pending', async () => {
    let resolve!: (v: unknown) => void;
    create.mockReturnValue(new Promise((r) => { resolve = r; }));
    setup();
    await fillValid();
    const btn = screen.getByRole('button', { name: 'Create ticket' });
    await userEvent.dblClick(btn);
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByRole('button', { name: /Create ticket/ })).toBeDisabled());
    expect(screen.getByRole('button', { name: /Create ticket/ })).toHaveAttribute('aria-busy', 'true');
    resolve(created);
    expect(await screen.findByText('detail page')).toBeInTheDocument();
    expect(create).toHaveBeenCalledTimes(1);
  });

  it('sends an Idempotency-Key and reuses the same key when retrying after a failure', async () => {
    create.mockRejectedValueOnce(new ApiError(503, 'INTERNAL_ERROR', 'temporary')).mockResolvedValueOnce(created);
    setup();
    await fillValid();
    await userEvent.click(screen.getByRole('button', { name: 'Create ticket' }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Create ticket' }));
    expect(await screen.findByText('detail page')).toBeInTheDocument();
    expect(create).toHaveBeenCalledTimes(2);
    const key1 = create.mock.calls[0][1] as string;
    const key2 = create.mock.calls[1][1] as string;
    expect(key1).toMatch(/.{8,}/);
    expect(key2).toBe(key1);
  });

  it('a fresh form mount uses a new idempotency key', async () => {
    create.mockResolvedValue(created);
    const a = setup();
    await fillValid();
    await userEvent.click(screen.getByRole('button', { name: 'Create ticket' }));
    await screen.findByText('detail page');
    a.unmount();
    setup();
    await fillValid();
    await userEvent.click(screen.getByRole('button', { name: 'Create ticket' }));
    await screen.findByText('detail page');
    expect(create.mock.calls[0][1]).not.toBe(create.mock.calls[1][1]);
  });

  it('maps server field errors onto the form', async () => {
    create.mockRejectedValueOnce(new ApiError(400, 'VALIDATION_FAILED', 'bad', [{ field: 'title', message: 'Title already used' }]));
    setup();
    await fillValid();
    await userEvent.click(screen.getByRole('button', { name: 'Create ticket' }));
    expect(await screen.findByText('Title already used')).toBeInTheDocument();
  });
});
