import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { kbApi } from '../api/endpoints';
import { describeError } from '../api/client';
import { useAuth, useTenantId } from '../auth/AuthContext';
import { atLeast, isCustomerOnly } from '../auth/roles';
import { Badge, Button, Card, EmptyState, ErrorNotice, Skeleton, useToast } from '../components/ui';
import { authorName, formatDateTime } from '../lib/format';
import { qk } from '../lib/queryKeys';

export default function KbDetailPage() {
  const { id = '' } = useParams();
  const { user } = useAuth();
  const t = useTenantId();
  const qc = useQueryClient();
  const toast = useToast();
  const staff = atLeast(user, 'AGENT') && !isCustomerOnly(user);
  const q = useQuery({ queryKey: qk.article(t, id), queryFn: () => kbApi.get(id) });
  const versions = useQuery({ queryKey: qk.articleVersions(t, id), queryFn: () => kbApi.versions(id), enabled: staff });
  const publish = useMutation({
    mutationFn: () => kbApi.publish(id),
    onSuccess: (a) => {
      qc.setQueryData(qk.article(t, id), a);
      void qc.invalidateQueries({ queryKey: qk.articles(t) });
      toast.push('success', 'Article published');
    },
    onError: (e) => toast.push('error', describeError(e)),
  });

  if (q.isLoading) return <Skeleton lines={8} label="Loading article" />;
  if (q.isError) return <ErrorNotice message={describeError(q.error)} onRetry={() => void q.refetch()} />;
  const a = q.data!;
  return (
    <article>
      <div className="page-head">
        <div>
          <p className="muted small"><Link to="/kb">← Articles</Link></p>
          <h1>{a.title}</h1>
          <p className="muted small">{a.categoryName ?? 'Uncategorized'} · {authorName(a.author)} · updated {formatDateTime(a.updatedAt)} · v{a.version}</p>
        </div>
        {staff && (
          <div className="btn-row">
            <Badge tone={a.status === 'PUBLISHED' ? 'success' : 'warn'}>{a.status}</Badge>
            <Badge tone={a.visibility === 'INTERNAL' ? 'internal' : 'neutral'}>{a.visibility}</Badge>
            <Link className="btn btn-secondary btn-md" to={`/kb/${a.id}/edit`}>Edit</Link>
            {a.status === 'DRAFT' && <Button onClick={() => publish.mutate()} loading={publish.isPending}>Publish</Button>}
          </div>
        )}
      </div>
      <Card>
        {a.summary && <p className="lead">{a.summary}</p>}
        <div className="article-body">{a.body}</div>
      </Card>
      {staff && (
        <Card title="Version history" className="mt">
          {versions.isLoading && <Skeleton lines={2} label="Loading versions" />}
          {versions.isError && <ErrorNotice message={describeError(versions.error)} />}
          {versions.data && versions.data.length === 0 && <EmptyState title="No previous versions" />}
          <ul className="plain-list">
            {versions.data?.map((v) => <li key={`${v.id}-${v.version}`}>v{v.version} · {v.title} · {formatDateTime(v.updatedAt)} · <Badge>{v.status}</Badge></li>)}
          </ul>
          {a.relatedTicketIds.length > 0 && <p className="small">Linked tickets: {a.relatedTicketIds.map((tid) => <Link key={tid} to={`/tickets/${tid}`}>{tid.slice(0, 8)} </Link>)}</p>}
        </Card>
      )}
    </article>
  );
}
