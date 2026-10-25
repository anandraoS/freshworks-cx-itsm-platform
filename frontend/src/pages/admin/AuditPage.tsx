import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { auditApi } from '../../api/endpoints';
import { describeError } from '../../api/client';
import type { AuditEvent } from '../../api/types';
import { useTenantId } from '../../auth/AuthContext';
import { Card, EmptyState, ErrorNotice, Input, Pagination, Skeleton, Table, type Column } from '../../components/ui';
import { formatDateTime } from '../../lib/format';
import { qk } from '../../lib/queryKeys';

const columns: Column<AuditEvent>[] = [
  { key: 'at', header: 'When', render: (e) => formatDateTime(e.at) },
  { key: 'actor', header: 'Actor', render: (e) => e.actorEmail },
  { key: 'action', header: 'Action', render: (e) => e.action },
  { key: 'entity', header: 'Entity', render: (e) => `${e.entityType} ${e.entityId ?? ''}` },
  { key: 'details', header: 'Details', render: (e) => e.details },
];

export default function AuditPage() {
  const t = useTenantId();
  const [page, setPage] = useState(0);
  const [entityType, setEntityType] = useState('');
  const filters = { page, size: 20, entityType: entityType || undefined };
  const q = useQuery({ queryKey: qk.audit(t, filters), queryFn: () => auditApi.list(filters), placeholderData: keepPreviousData });
  return (
    <Card title="Audit activity">
      <div className="filters">
        <Input label="Entity type" placeholder="e.g. TICKET" value={entityType} onChange={(e) => { setEntityType(e.target.value.toUpperCase()); setPage(0); }} />
      </div>
      {q.isLoading && <Skeleton lines={6} label="Loading audit events" />}
      {q.isError && <ErrorNotice message={describeError(q.error)} onRetry={() => void q.refetch()} />}
      {q.data && q.data.content.length === 0 && <EmptyState title="No audit events" />}
      {q.data && q.data.content.length > 0 && (
        <>
          <Table caption="Audit events" columns={columns} rows={q.data.content} rowKey={(e) => e.id} />
          <Pagination page={q.data.page} totalPages={q.data.totalPages} totalElements={q.data.totalElements} onPage={setPage} />
        </>
      )}
    </Card>
  );
}
