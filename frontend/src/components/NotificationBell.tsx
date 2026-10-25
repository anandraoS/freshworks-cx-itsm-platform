import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMarkRead, useNotificationList, useUnreadCount } from '../hooks/useNotifications';
import { formatDateTime } from '../lib/format';

export function NotificationBell() {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const btnRef = useRef<HTMLButtonElement>(null);
  const navigate = useNavigate();
  const count = useUnreadCount();
  const list = useNotificationList(false, open);
  const mark = useMarkRead();

  useEffect(() => {
    if (!open) return;
    function onDoc(e: MouseEvent) { if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false); }
    function onKey(e: KeyboardEvent) { if (e.key === 'Escape') { setOpen(false); btnRef.current?.focus(); } }
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', onDoc); document.removeEventListener('keydown', onKey); };
  }, [open]);

  const unread = count.data ?? 0;
  return (
    <div className="bell" ref={wrapRef}>
      <button ref={btnRef} type="button" className="bell-btn" aria-haspopup="true" aria-expanded={open}
        aria-label={`Notifications, ${unread} unread`} onClick={() => setOpen((o) => !o)}>
        <span aria-hidden="true">🔔</span>
        {unread > 0 && <span className="bell-count" data-testid="unread-count">{unread > 99 ? '99+' : unread}</span>}
      </button>
      {open && (
        <div className="bell-panel" role="region" aria-label="Recent notifications">
          {list.isLoading && <p className="muted">Loading…</p>}
          {list.isError && <p className="muted">Could not load notifications.</p>}
          {list.data && list.data.content.length === 0 && <p className="muted">You are all caught up.</p>}
          <ul>
            {list.data?.content.slice(0, 8).map((n) => (
              <li key={n.id} className={n.read ? 'read' : 'unread'}>
                <button type="button" className="bell-item" onClick={() => {
                  if (!n.read) mark.mutate(n.id);
                  if (n.ticketId) { setOpen(false); navigate(`/tickets/${n.ticketId}`); }
                }}>
                  <strong>{n.title}</strong>
                  <span>{n.body}</span>
                  <small>{formatDateTime(n.createdAt)}</small>
                </button>
              </li>
            ))}
          </ul>
          <Link to="/notifications" onClick={() => setOpen(false)} className="bell-all">View all notifications</Link>
        </div>
      )}
    </div>
  );
}
