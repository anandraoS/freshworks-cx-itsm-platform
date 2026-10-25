import { useState } from 'react';
import { Link } from 'react-router-dom';
import { describeError } from '../api/client';
import { Badge, Button, Card, EmptyState, ErrorNotice, Skeleton } from '../components/ui';
import { useMarkRead, useNotificationList } from '../hooks/useNotifications';
import { formatDateTime } from '../lib/format';

export default function NotificationsPage() {
  const [unreadOnly, setUnreadOnly] = useState(false);
  const q = useNotificationList(unreadOnly);
  const mark = useMarkRead();
  return (
    <div>
      <div className="page-head">
        <h1>Notifications</h1>
        <label className="inline-check"><input type="checkbox" checked={unreadOnly} onChange={(e) => setUnreadOnly(e.target.checked)} /> Unread only</label>
      </div>
      <Card>
        {q.isLoading && <Skeleton lines={5} label="Loading notifications" />}
        {q.isError && <ErrorNotice message={describeError(q.error)} onRetry={() => void q.refetch()} />}
        {q.data && q.data.content.length === 0 && <EmptyState title="No notifications" description="You are all caught up." />}
        <ul className="notif-list">
          {q.data?.content.map((n) => (
            <li key={n.id} className={n.read ? 'read' : 'unread'}>
              <div>
                <strong>{n.title}</strong> {!n.read && <Badge tone="info">New</Badge>}
                <p>{n.body}</p>
                <small className="muted">{formatDateTime(n.createdAt)}</small>
                {n.ticketId && <> · <Link to={`/tickets/${n.ticketId}`}>Open ticket</Link></>}
              </div>
              {!n.read && <Button size="sm" variant="secondary" onClick={() => mark.mutate(n.id)}>Mark as read</Button>}
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
