import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { NotificationBell } from '../components/NotificationBell';
import { makeUser, renderApp } from './utils';
import type { AppNotification } from '../api/types';

vi.mock('../api/endpoints', () => ({
  notificationApi: { list: vi.fn(), unreadCount: vi.fn(), markRead: vi.fn() },
}));
import { notificationApi } from '../api/endpoints';
const api = notificationApi as unknown as Record<'list' | 'unreadCount' | 'markRead', ReturnType<typeof vi.fn>>;

const n = (id: string, read = false): AppNotification => ({ id, type: 'TICKET_ASSIGNED', title: `Title ${id}`, body: 'body', read, createdAt: '2026-01-01T00:00:00Z', ticketId: null });
let unread = 2;

beforeEach(() => {
  Object.values(api).forEach((f) => f.mockReset());
  unread = 2;
  api.unreadCount.mockImplementation(() => Promise.resolve({ count: unread }));
  api.list.mockResolvedValue({ content: [n('1'), n('2')], page: 0, size: 20, totalElements: 2, totalPages: 1 });
});

describe('NotificationBell', () => {
  it('shows unread count from the API and a labelled button', async () => {
    renderApp(<NotificationBell />, { user: makeUser(['AGENT']) });
    expect(await screen.findByTestId('unread-count')).toHaveTextContent('2');
    expect(screen.getByRole('button', { name: 'Notifications, 2 unread' })).toBeInTheDocument();
  });

  it('optimistically marks read and decrements the badge before the server answers', async () => {
    let finish!: () => void;
    api.markRead.mockReturnValue(new Promise<void>((r) => { finish = () => { unread = 1; r(); }; }));
    renderApp(<NotificationBell />, { user: makeUser(['AGENT']) });
    await screen.findByTestId('unread-count');
    await userEvent.click(screen.getByRole('button', { name: /Notifications/ }));
    await userEvent.click(await screen.findByRole('button', { name: /Title 1/ }));
    await waitFor(() => expect(screen.getByTestId('unread-count')).toHaveTextContent('1')); // before the request resolved
    finish();
  });

  it('rolls back the optimistic update when the request fails', async () => {
    api.markRead.mockRejectedValue(new Error('network'));
    renderApp(<NotificationBell />, { user: makeUser(['AGENT']) });
    await screen.findByTestId('unread-count');
    await userEvent.click(screen.getByRole('button', { name: /Notifications/ }));
    await userEvent.click(await screen.findByRole('button', { name: /Title 1/ }));
    await waitFor(() => expect(api.markRead).toHaveBeenCalledWith('1'));
    await waitFor(() => expect(screen.getByTestId('unread-count')).toHaveTextContent('2'));
    expect(screen.getByRole('button', { name: /Title 1/ }).closest('li')).toHaveClass('unread');
  });
});
