import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { kbApi, type ArticleFilters } from '../api/endpoints';
import { describeError } from '../api/client';
import { useAuth, useTenantId } from '../auth/AuthContext';
import { atLeast, isCustomerOnly } from '../auth/roles';
import { Badge, Card, EmptyState, ErrorNotice, Input, Pagination, Select, Skeleton } from '../components/ui';
import { useArticleCategories } from '../hooks/useLookups';
import { authorName, formatDateTime } from '../lib/format';
import { qk } from '../lib/queryKeys';

export default function KbListPage() {
  const { user } = useAuth();
  const t = useTenantId();
  const customer = isCustomerOnly(user);
  const [sp, setSp] = useSearchParams();
  const q = sp.get('q') ?? '';
  const categoryId = sp.get('categoryId') ?? '';
  const status = sp.get('status') ?? '';
  const page = Math.max(0, parseInt(sp.get('page') ?? '0', 10) || 0);
  const [search, setSearch] = useState(q);
  useEffect(() => setSearch(q), [q]);

  function update(patch: Record<string, string | undefined>) {
    const n = new URLSearchParams(sp);
    for (const [k, v] of Object.entries(patch)) { if (v) n.set(k, v); else n.delete(k); }
    setSp(n);
  }
  useEffect(() => {
    if (search === q) return;
    const h = setTimeout(() => update({ q: search || undefined, page: undefined }), 300);
    return () => clearTimeout(h);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);

  const filters: ArticleFilters = { page, size: 10, q: q || undefined, categoryId: categoryId || undefined, status: customer ? 'PUBLISHED' : status || undefined, visibility: customer ? 'PUBLIC' : undefined };
  const query = useQuery({ queryKey: qk.articleList(t, filters), queryFn: () => kbApi.list(filters), placeholderData: keepPreviousData });
  const cats = useArticleCategories();

  return (
    <div>
      <div className="page-head">
        <h1>{customer ? 'Help articles' : 'Knowledge base'}</h1>
        {atLeast(user, 'AGENT') && !customer && <Link className="btn btn-primary btn-md" to="/kb/new">New article</Link>}
      </div>
      <Card>
        <form className="filters" role="search" aria-label="Filter articles" onSubmit={(e) => e.preventDefault()}>
          <Input label="Search" type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search articles" />
          <Select label="Category" value={categoryId} onChange={(e) => update({ categoryId: e.target.value || undefined, page: undefined })}>
            <option value="">All</option>
            {cats.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </Select>
          {!customer && (
            <Select label="Status" value={status} onChange={(e) => update({ status: e.target.value || undefined, page: undefined })}>
              <option value="">All</option><option value="DRAFT">Draft</option><option value="PUBLISHED">Published</option>
            </Select>
          )}
        </form>
      </Card>
      <Card className="mt">
        {query.isLoading && <Skeleton lines={6} label="Loading articles" />}
        {query.isError && <ErrorNotice message={describeError(query.error)} onRetry={() => void query.refetch()} />}
        {query.data && query.data.content.length === 0 && <EmptyState title="No articles found" description="Try a different search or category." />}
        <ul className="article-list">
          {query.data?.content.map((a) => (
            <li key={a.id}>
              <h2 className="article-title"><Link to={`/kb/${a.id}`}>{a.title}</Link></h2>
              <p>{a.summary}</p>
              <p className="small muted">
                {a.categoryName ?? 'Uncategorized'} · {authorName(a.author)} · {formatDateTime(a.updatedAt)}{' '}
                {!customer && <><Badge tone={a.status === 'PUBLISHED' ? 'success' : 'warn'}>{a.status}</Badge> <Badge tone={a.visibility === 'INTERNAL' ? 'internal' : 'neutral'}>{a.visibility}</Badge></>}
              </p>
            </li>
          ))}
        </ul>
        {query.data && <Pagination page={query.data.page} totalPages={query.data.totalPages} totalElements={query.data.totalElements} onPage={(p) => update({ page: p ? String(p) : undefined })} />}
      </Card>
    </div>
  );
}
