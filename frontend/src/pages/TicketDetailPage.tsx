import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ticketApi } from '../api/endpoints';
import { ApiError, describeError } from '../api/client';
import { DEFAULT_TRANSITIONS, type Ticket, type TicketStatus } from '../api/types';
import { useAuth, useTenantId } from '../auth/AuthContext';
import { atLeast, isCustomerOnly } from '../auth/roles';
import { AiPanel } from '../components/AiPanel';
import { AiBadge, Badge, Button, Card, EmptyState, ErrorNotice, Modal, PriorityBadge, Select, Skeleton, SlaIndicator, StatusBadge, Tabs, Textarea, useToast } from '../components/ui';
import { useAssignableUsers } from '../hooks/useLookups';
import { formatDateTime, humanize } from '../lib/format';
import { qk } from '../lib/queryKeys';
import { commentSchema, transitionSchema } from '../lib/schemas';

export default function TicketDetailPage() {
  const { id = '' } = useParams();
  const t = useTenantId();
  const qc = useQueryClient();
  const { user } = useAuth();
  const customer = isCustomerOnly(user);
  const staff = atLeast(user, 'AGENT');
  const [conflict, setConflict] = useState(false);

  const ticket = useQuery({ queryKey: qk.ticket(t, id), queryFn: () => ticketApi.get(id) });

  function reloadLatest() {
    setConflict(false);
    void qc.invalidateQueries({ queryKey: qk.ticket(t, id) });
    void qc.invalidateQueries({ queryKey: qk.tickets(t) });
  }
  const onConflict = () => setConflict(true);

  if (ticket.isLoading) return <Skeleton lines={10} label="Loading ticket" />;
  if (ticket.isError) {
    const nf = ticket.error instanceof ApiError && ticket.error.kind === 'not_found';
    return nf
      ? <EmptyState title="Ticket not found" description="It may not exist, or you may not have access to it." action={<Link to="/tickets">Back to tickets</Link>} />
      : <ErrorNotice message={describeError(ticket.error)} onRetry={() => void ticket.refetch()} />;
  }
  const tk = ticket.data as Ticket;

  return (
    <div>
      <div className="page-head">
        <div>
          <p className="muted small"><Link to="/tickets">← Tickets</Link></p>
          <h1>#{tk.number} {tk.title}</h1>
        </div>
        {staff && <Link className="btn btn-secondary btn-md" to={`/tickets/${tk.id}/edit`}>Edit</Link>}
      </div>

      {conflict && (
        <div className="notice notice-warn" role="alert" data-testid="conflict-banner">
          <span>This ticket was modified by someone else. Your change was not applied. Reload to see the latest version, then try again.</span>
          <Button size="sm" onClick={reloadLatest}>Reload latest</Button>
        </div>
      )}

      <div className="detail-grid">
        <div className="detail-main">
          <Card title="Details">
            <div className="badges">
              <StatusBadge status={tk.status} /> <PriorityBadge priority={tk.priority} /> <Badge>{humanize(tk.type)}</Badge> <Badge>{`Severity: ${humanize(tk.severity)}`}</Badge>
              {tk.reopenCount > 0 && <Badge tone="warn">{`Reopened ${tk.reopenCount}x`}</Badge>}
              {tk.escalationLevel > 0 && <Badge tone="danger">{`Escalation L${tk.escalationLevel}`}</Badge>}
            </div>
            <p className="description">{tk.description}</p>
            <dl className="meta">
              <div><dt>Requester</dt><dd>{tk.requester.displayName}</dd></div>
              <div><dt>Category</dt><dd>{tk.categoryName ?? '—'}</dd></div>
              <div><dt>Created</dt><dd>{formatDateTime(tk.createdAt)}</dd></div>
              <div><dt>Updated</dt><dd>{formatDateTime(tk.updatedAt)}</dd></div>
              <div><dt>Tags</dt><dd>{tk.tags.length ? tk.tags.map((x) => <Badge key={x}>{x}</Badge>) : '—'}</dd></div>
              <div><dt>Attachments</dt><dd>{tk.attachments.length ? tk.attachments.map((a) => a.fileName).join(', ') : '—'}</dd></div>
            </dl>
          </Card>

          <CommentsCard ticketId={tk.id} customer={customer} />
          <ActivityCard ticketId={tk.id} />
        </div>

        <aside className="detail-side">
          <Card title="SLA">
            <dl className="meta meta-col">
              <div><dt>Status</dt><dd><SlaIndicator status={tk.slaStatus} /></dd></div>
              <div><dt>First response due</dt><dd>{formatDateTime(tk.firstResponseDueAt)}{!tk.firstRespondedAt && tk.firstResponseDueAt && <> <SlaIndicator status={tk.slaStatus} due={tk.firstResponseDueAt} /></>}{tk.firstRespondedAt && <small className="muted"> (responded {formatDateTime(tk.firstRespondedAt)})</small>}</dd></div>
              <div><dt>Resolution due</dt><dd>{formatDateTime(tk.resolutionDueAt)}{!tk.resolvedAt && tk.resolutionDueAt && <> <SlaIndicator status={tk.slaStatus} due={tk.resolutionDueAt} /></>}{tk.resolvedAt && <small className="muted"> (resolved {formatDateTime(tk.resolvedAt)})</small>}</dd></div>
            </dl>
          </Card>
          {staff && (
            <>
              <AssignCard key={tk.version} ticket={tk} onConflict={onConflict} />
              <TransitionCard ticket={tk} onConflict={onConflict} />
              <AiPanel ticketId={tk.id} />
            </>
          )}
        </aside>
      </div>
    </div>
  );
}

function useTicketMutationHandlers(ticketId: string, onConflict: () => void) {
  const t = useTenantId();
  const qc = useQueryClient();
  const toast = useToast();
  return {
    onSuccess: (updated: Ticket, message: string) => {
      qc.setQueryData(qk.ticket(t, ticketId), updated);
      void qc.invalidateQueries({ queryKey: qk.tickets(t) });
      toast.push('success', message);
    },
    onError: (e: unknown) => {
      if (e instanceof ApiError && e.kind === 'version_conflict') { onConflict(); return; }
      toast.push('error', describeError(e));
    },
  };
}

function AssignCard({ ticket, onConflict }: { ticket: Ticket; onConflict: () => void }) {
  const users = useAssignableUsers();
  const h = useTicketMutationHandlers(ticket.id, onConflict);
  const qc = useQueryClient();
  const t = useTenantId();
  const [value, setValue] = useState(ticket.assignee?.id ?? '');
  const m = useMutation({
    mutationFn: (assigneeId: string | null) => ticketApi.assign(ticket.id, assigneeId, ticket.version),
    onSuccess: (u) => { h.onSuccess(u, 'Assignment updated'); void qc.invalidateQueries({ queryKey: qk.activity(t, ticket.id) }); },
    onError: h.onError,
  });
  const dirty = value !== (ticket.assignee?.id ?? '');
  return (
    <Card title="Assignment">
      <p className="small">Currently: <strong>{ticket.assignee?.displayName ?? 'Unassigned'}</strong></p>
      <Select label="Assignee" value={value} onChange={(e) => setValue(e.target.value)} disabled={m.isPending}>
        <option value="">Unassigned</option>
        {users.data?.filter((u) => u.active !== false && u.roles.some((r) => r !== 'CUSTOMER')).map((u) => <option key={u.id} value={u.id}>{u.displayName}</option>)}
      </Select>
      <Button onClick={() => m.mutate(value || null)} disabled={!dirty || m.isPending} loading={m.isPending}>Save assignment</Button>
    </Card>
  );
}

function TransitionCard({ ticket, onConflict }: { ticket: Ticket; onConflict: () => void }) {
  const t = useTenantId();
  const qc = useQueryClient();
  const h = useTicketMutationHandlers(ticket.id, onConflict);
  const [target, setTarget] = useState<TicketStatus | null>(null);
  const [serverError, setServerError] = useState<string | null>(null);
  const { register, handleSubmit, reset, formState: { errors } } = useForm<{ reason: string }>({ resolver: zodResolver(transitionSchema), defaultValues: { reason: '' } });
  const allowed = DEFAULT_TRANSITIONS[ticket.status] ?? [];

  const m = useMutation({
    mutationFn: (v: { reason: string }) => ticketApi.transition(ticket.id, target as TicketStatus, v.reason.trim(), ticket.version),
    onSuccess: (u) => { h.onSuccess(u, `Status changed to ${humanize(u.status)}`); void qc.invalidateQueries({ queryKey: qk.activity(t, ticket.id) }); close(); },
    onError: (e) => {
      if (e instanceof ApiError && e.kind === 'invalid_transition') { setServerError(e.message || 'That transition is not allowed.'); return; }
      close();
      h.onError(e);
    },
  });
  function close() { setTarget(null); setServerError(null); reset(); }

  return (
    <Card title="Status">
      {allowed.length === 0 ? <p className="muted">No transitions available.</p> : (
        <div className="btn-row" role="group" aria-label="Change status">
          {allowed.map((s) => <Button key={s} variant="secondary" size="sm" onClick={() => setTarget(s)}>{humanize(s)}</Button>)}
        </div>
      )}
      <Modal open={target !== null} title={`Move to ${target ? humanize(target) : ''}`} onClose={close}>
        <form onSubmit={handleSubmit((v) => m.mutate(v))} noValidate aria-label="Transition reason">
          <Textarea label="Reason" required rows={3} data-autofocus {...register('reason')} error={errors.reason?.message} />
          {serverError && <p className="notice notice-error" role="alert">{serverError}</p>}
          <div className="modal-footer">
            <Button variant="secondary" onClick={close}>Cancel</Button>
            <Button type="submit" loading={m.isPending} disabled={m.isPending}>Confirm</Button>
          </div>
        </form>
      </Modal>
    </Card>
  );
}

function CommentsCard({ ticketId, customer }: { ticketId: string; customer: boolean }) {
  const t = useTenantId();
  const qc = useQueryClient();
  const toast = useToast();
  const [tab, setTab] = useState<'PUBLIC' | 'INTERNAL'>('PUBLIC');
  const comments = useQuery({ queryKey: qk.comments(t, ticketId), queryFn: () => ticketApi.comments(ticketId) });
  const { register, handleSubmit, reset, formState: { errors } } = useForm<{ body: string }>({ resolver: zodResolver(commentSchema), defaultValues: { body: '' } });
  const m = useMutation({
    mutationFn: (v: { body: string }) => ticketApi.addComment(ticketId, v.body.trim(), customer ? 'PUBLIC' : tab),
    onSuccess: () => {
      reset();
      void qc.invalidateQueries({ queryKey: qk.comments(t, ticketId) });
      void qc.invalidateQueries({ queryKey: qk.activity(t, ticketId) });
      toast.push('success', 'Comment added');
    },
    onError: (e) => toast.push('error', describeError(e)),
  });

  const all = comments.data ?? [];
  const active = customer ? 'PUBLIC' : tab;
  const shown = all.filter((c) => c.visibility === active);
  const tabs = customer
    ? [{ id: 'PUBLIC', label: 'Conversation', badge: shown.length }]
    : [
        { id: 'PUBLIC', label: 'Public replies', badge: all.filter((c) => c.visibility === 'PUBLIC').length, tone: 'public' as const },
        { id: 'INTERNAL', label: 'Internal notes', badge: all.filter((c) => c.visibility === 'INTERNAL').length, tone: 'internal' as const },
      ];

  return (
    <Card title="Comments" className={`comments comments-${active.toLowerCase()}`}>
      <Tabs tabs={tabs} active={active} onChange={(id) => setTab(id as 'PUBLIC' | 'INTERNAL')} idBase="comments" label="Comment visibility" />
      <div role="tabpanel" id={`comments-panel-${active}`} aria-labelledby={`comments-tab-${active}`} tabIndex={0}>
        {active === 'INTERNAL'
          ? <p className="banner-internal" role="note">Internal notes are visible to staff only and never shown to customers.</p>
          : !customer && <p className="banner-public" role="note">Public replies are visible to the customer.</p>}
        {comments.isLoading && <Skeleton lines={3} label="Loading comments" />}
        {comments.isError && <ErrorNotice message={describeError(comments.error)} onRetry={() => void comments.refetch()} />}
        {comments.data && shown.length === 0 && <EmptyState title={active === 'INTERNAL' ? 'No internal notes yet' : 'No replies yet'} />}
        <ul className="comment-list">
          {shown.map((c) => (
            <li key={c.id} className={`comment comment-${c.visibility.toLowerCase()}`}>
              <div className="comment-head">
                <strong>{c.authorName}</strong>
                {c.visibility === 'INTERNAL' && <Badge tone="internal">Internal</Badge>}
                {c.aiGenerated && <AiBadge>AI-generated, approved by agent</AiBadge>}
                <time dateTime={c.createdAt} className="muted small">{formatDateTime(c.createdAt)}</time>
              </div>
              <p>{c.body}</p>
            </li>
          ))}
        </ul>
        <form onSubmit={handleSubmit((v) => m.mutate(v))} noValidate aria-label={`Add ${active === 'INTERNAL' ? 'internal note' : 'public reply'}`}>
          <Textarea label={active === 'INTERNAL' ? 'Add internal note' : customer ? 'Add a reply' : 'Add public reply'} rows={3} {...register('body')} error={errors.body?.message} />
          <Button type="submit" loading={m.isPending} disabled={m.isPending}>{active === 'INTERNAL' ? 'Add internal note' : 'Post reply'}</Button>
        </form>
      </div>
    </Card>
  );
}

function ActivityCard({ ticketId }: { ticketId: string }) {
  const t = useTenantId();
  const q = useQuery({ queryKey: qk.activity(t, ticketId), queryFn: () => ticketApi.activity(ticketId) });
  return (
    <Card title="Activity">
      {q.isLoading && <Skeleton lines={3} label="Loading activity" />}
      {q.isError && <ErrorNotice message={describeError(q.error)} />}
      {q.data && q.data.length === 0 && <EmptyState title="No activity yet" />}
      <ol className="timeline">
        {q.data?.map((a) => (
          <li key={a.id}>
            <span className="tl-dot" aria-hidden="true" />
            <div><strong>{humanize(a.type)}</strong> by {a.actorName}<br /><span className="muted small">{a.details}</span><br />
              <time dateTime={a.at} className="muted small">{formatDateTime(a.at)}</time></div>
          </li>
        ))}
      </ol>
    </Card>
  );
}
