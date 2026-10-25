import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notificationApi } from '../api/endpoints';
import type { AppNotification, Page } from '../api/types';
import { useTenantId } from '../auth/AuthContext';
import { qk } from '../lib/queryKeys';

export const NOTIFICATION_POLL_MS = 30_000;

export function useUnreadCount() {
  const t = useTenantId();
  return useQuery({
    queryKey: qk.unreadCount(t),
    queryFn: notificationApi.unreadCount,
    refetchInterval: NOTIFICATION_POLL_MS,
    refetchIntervalInBackground: false,
    select: (d) => d.count,
  });
}

export function useNotificationList(unreadOnly: boolean, enabled = true) {
  const t = useTenantId();
  return useQuery({
    queryKey: qk.notificationList(t, unreadOnly),
    queryFn: () => notificationApi.list(unreadOnly),
    enabled,
    refetchInterval: enabled ? NOTIFICATION_POLL_MS : false,
  });
}

/**
 * Optimistic read mark (the ONLY optimistic update in the app): flips `read` in every cached list and
 * decrements the badge immediately; rolls both back if the request fails; re-syncs from server when settled.
 */
export function useMarkRead() {
  const qc = useQueryClient();
  const t = useTenantId();
  return useMutation({
    mutationFn: (id: string) => notificationApi.markRead(id),
    onMutate: async (id) => {
      await qc.cancelQueries({ queryKey: qk.notifications(t) });
      const lists = qc.getQueriesData<Page<AppNotification>>({ queryKey: ['t', t, 'notifications', 'list'] });
      const prevCount = qc.getQueryData<{ count: number }>(qk.unreadCount(t));
      let wasUnread = false;
      for (const [key, data] of lists) {
        if (!data) continue;
        if (data.content.some((n) => n.id === id && !n.read)) wasUnread = true;
        qc.setQueryData<Page<AppNotification>>(key, { ...data, content: data.content.map((n) => (n.id === id ? { ...n, read: true } : n)) });
      }
      if (prevCount && wasUnread) qc.setQueryData(qk.unreadCount(t), { count: Math.max(0, prevCount.count - 1) });
      return { lists, prevCount };
    },
    onError: (_e, _id, ctx) => {
      ctx?.lists.forEach(([key, data]) => qc.setQueryData(key, data));
      if (ctx?.prevCount) qc.setQueryData(qk.unreadCount(t), ctx.prevCount);
    },
    onSettled: () => { void qc.invalidateQueries({ queryKey: qk.notifications(t) }); },
  });
}
