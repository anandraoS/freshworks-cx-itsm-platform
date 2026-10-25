import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { kbApi, ticketApi } from '../api/endpoints';
import { describeError } from '../api/client';
import { useAuth, useTenantId } from '../auth/AuthContext';
import { Card, EmptyState, ErrorNotice, PriorityBadge, Skeleton, StatusBadge } from '../components/ui';
import { formatDateTime } from '../lib/format';
import { qk } from '../lib/queryKeys';

/** Customer portal landing: own tickets (server-filtered with mine=true) + public published articles. */
export default function PortalPage() {
  const { user } = useAuth();
  const t = useTenantId();
  const tickets = useQuery({ queryKey: qk.ticketList(t, { portal: true }), queryFn: () => ticketApi.list({ mine: true, size: 5, sort: 'createdAt,desc' }) });
  const articles = useQuery({ queryKey: qk.articleList(t, { portal: true }), queryFn: () => kbApi.list({ size: 5, status: 'PUBLISHED', visibility: 'PUBLIC' }) });

  return (
    <div>
      <div className="page-head">
        <h1>Welcome, {user?.displayName}</h1>
        <Link className="btn btn-primary btn-md" to="/tickets/new">Create a ticket</Link>
      </div>
      <div className="dash-grid">
        <Card title="My recent tickets" actions={<Link to="/tickets">View all</Link>}>
          {tickets.isLoading && <Skeleton label="Loading your tickets" />}
          {tickets.isError && <ErrorNotice message={describeError(tickets.error)} />}
          {tickets.data?.content.length === 0 && <EmptyState title="You have no tickets" description="Need help? Create a ticket and we will get back to you." />}
          <ul className="plain-list">
            {tickets.data?.content.map((tk) => (
              <li key={tk.id}><Link to={`/tickets/${tk.id}`}>#{tk.number} {tk.title}</Link> <StatusBadge status={tk.status} /> <PriorityBadge priority={tk.priority} /> <span className="muted small">{formatDateTime(tk.updatedAt)}</span></li>
            ))}
          </ul>
        </Card>
        <Card title="Help articles" actions={<Link to="/kb">Browse all</Link>}>
          {articles.isLoading && <Skeleton label="Loading articles" />}
          {articles.isError && <ErrorNotice message={describeError(articles.error)} />}
          {articles.data?.content.length === 0 && <EmptyState title="No articles yet" />}
          <ul className="plain-list">
            {articles.data?.content.map((a) => <li key={a.id}><Link to={`/kb/${a.id}`}>{a.title}</Link><br /><span className="muted small">{a.summary}</span></li>)}
          </ul>
        </Card>
      </div>
    </div>
  );
}
