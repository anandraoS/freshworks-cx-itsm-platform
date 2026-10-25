import { useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ticketApi } from '../api/endpoints';
import { ApiError, describeError } from '../api/client';
import { PRIORITIES, SEVERITIES, TICKET_TYPES, type Attachment, type Priority, type Severity, type TicketType } from '../api/types';
import { useTenantId } from '../auth/AuthContext';
import { Button, Card, ErrorNotice, Input, Select, Skeleton, Textarea, useToast } from '../components/ui';
import { useCategories } from '../hooks/useLookups';
import { newId } from '../lib/correlation';
import { applyFieldErrors } from '../lib/formErrors';
import { humanize } from '../lib/format';
import { qk } from '../lib/queryKeys';
import { parseTags, ticketSchema, type TicketForm } from '../lib/schemas';

const MAX_ATTACHMENTS = 5;
const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;
const FIELDS = ['type', 'title', 'description', 'priority', 'severity', 'categoryId', 'tags'];

export default function TicketFormPage() {
  const { id } = useParams();
  const editing = !!id;
  const t = useTenantId();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const toast = useToast();
  const categories = useCategories();

  // One Idempotency-Key per form mount; reused for every retry of the same submit (server replays the original response).
  const idempotencyKey = useRef(newId());
  const submitting = useRef(false); // synchronous guard against double-click before React re-renders
  const [formError, setFormError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [attachError, setAttachError] = useState<string | null>(null);

  const existing = useQuery({ queryKey: qk.ticket(t, id ?? 'new'), queryFn: () => ticketApi.get(id as string), enabled: editing });

  const { register, handleSubmit, reset, setError, formState: { errors } } = useForm<TicketForm>({
    resolver: zodResolver(ticketSchema),
    defaultValues: { type: 'SUPPORT', title: '', description: '', priority: 'MEDIUM', severity: 'MINOR', categoryId: '', tags: '' },
  });

  useEffect(() => {
    const tk = existing.data;
    if (tk) reset({ type: tk.type, title: tk.title, description: tk.description, priority: tk.priority, severity: tk.severity, categoryId: tk.categoryId ?? '', tags: tk.tags.join(', ') });
  }, [existing.data, reset]);

  const mutation = useMutation({
    mutationFn: (v: TicketForm) => {
      const base = {
        title: v.title.trim(), description: v.description.trim(), priority: v.priority as Priority, severity: v.severity as Severity,
        categoryId: v.categoryId || null, tags: parseTags(v.tags),
      };
      if (editing && existing.data) return ticketApi.update(id as string, { ...base, version: existing.data.version });
      return ticketApi.create({ ...base, type: v.type as TicketType, attachments }, idempotencyKey.current);
    },
    onSuccess: (ticket) => {
      void qc.invalidateQueries({ queryKey: qk.tickets(t) });
      toast.push('success', editing ? 'Ticket updated' : `Ticket #${ticket.number} created`);
      navigate(`/tickets/${ticket.id}`);
    },
    onError: (e) => {
      if (e instanceof ApiError && e.kind === 'version_conflict' && editing) { setConflict(true); return; }
      if (e instanceof ApiError && e.kind === 'duplicate') { setFormError('This ticket was already submitted. Check the ticket list before trying again.'); return; }
      if (!applyFieldErrors(e, setError, FIELDS)) setFormError(describeError(e));
    },
    onSettled: () => { submitting.current = false; },
  });

  function onSubmit(v: TicketForm) {
    if (submitting.current || mutation.isPending) return;
    submitting.current = true;
    setFormError(null);
    mutation.mutate(v);
  }

  function onFiles(files: FileList | null) {
    setAttachError(null);
    if (!files) return;
    const next = [...attachments];
    for (const f of Array.from(files)) {
      if (next.length >= MAX_ATTACHMENTS) { setAttachError(`At most ${MAX_ATTACHMENTS} attachments`); break; }
      if (f.size > MAX_ATTACHMENT_BYTES) { setAttachError(`${f.name} is larger than 10 MB`); continue; }
      next.push({ fileName: f.name, contentType: f.type || 'application/octet-stream', sizeBytes: f.size });
    }
    setAttachments(next);
  }

  if (editing && existing.isLoading) return <Skeleton lines={8} label="Loading ticket" />;
  if (editing && existing.isError) return <ErrorNotice message={describeError(existing.error)} />;

  return (
    <div>
      <div className="page-head"><h1>{editing ? `Edit ticket #${existing.data?.number}` : 'New ticket'}</h1></div>
      <Card>
        {conflict && (
          <div className="notice notice-warn" role="alert">
            <span>Someone else changed this ticket while you were editing. Reload the latest version (your edits on this form will be replaced).</span>
            <Button size="sm" onClick={() => { setConflict(false); void existing.refetch(); }}>Reload latest</Button>
          </div>
        )}
        <form onSubmit={handleSubmit(onSubmit)} noValidate aria-label={editing ? 'Edit ticket' : 'Create ticket'} className="form-grid">
          {editing ? (
            <div className="field"><span className="label-like">Type</span><p>{humanize(existing.data?.type ?? 'SUPPORT')}</p></div>
          ) : (
            <Select label="Type" required {...register('type')} error={errors.type?.message}>
              {TICKET_TYPES.map((x) => <option key={x} value={x}>{humanize(x)}</option>)}
            </Select>
          )}
          <Select label="Category" {...register('categoryId')} error={errors.categoryId?.message}>
            <option value="">No category</option>
            {categories.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </Select>
          <div className="span-2"><Input label="Title" required maxLength={200} {...register('title')} error={errors.title?.message} /></div>
          <div className="span-2"><Textarea label="Description" required rows={6} {...register('description')} error={errors.description?.message} /></div>
          <Select label="Priority" required {...register('priority')} error={errors.priority?.message}>
            {PRIORITIES.map((x) => <option key={x} value={x}>{humanize(x)}</option>)}
          </Select>
          <Select label="Severity" required {...register('severity')} error={errors.severity?.message}>
            {SEVERITIES.map((x) => <option key={x} value={x}>{humanize(x)}</option>)}
          </Select>
          <div className="span-2"><Input label="Tags" hint="Comma separated, up to 10" {...register('tags')} error={errors.tags?.message} /></div>
          {!editing && (
            <div className="span-2 field">
              <label htmlFor="attachments">Attachments (metadata only in this demo)</label>
              <input id="attachments" type="file" multiple onChange={(e) => { onFiles(e.target.files); e.target.value = ''; }} />
              {attachError && <p className="field-msg" role="alert">{attachError}</p>}
              {attachments.length > 0 && (
                <ul className="chips">
                  {attachments.map((a, i) => (
                    <li key={a.fileName + i}>{a.fileName} <button type="button" className="link-btn" aria-label={`Remove ${a.fileName}`} onClick={() => setAttachments(attachments.filter((_, j) => j !== i))}>×</button></li>
                  ))}
                </ul>
              )}
            </div>
          )}
          {formError && <p className="notice notice-error span-2" role="alert">{formError}</p>}
          <div className="span-2 form-actions">
            <Button type="submit" loading={mutation.isPending} disabled={mutation.isPending}>{editing ? 'Save changes' : 'Create ticket'}</Button>
            <Link className="btn btn-secondary btn-md" to={editing ? `/tickets/${id}` : '/tickets'}>Cancel</Link>
          </div>
        </form>
      </Card>
    </div>
  );
}
