import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { slaApi } from '../../api/endpoints';
import { describeError } from '../../api/client';
import type { SlaPolicy } from '../../api/types';
import { useTenantId } from '../../auth/AuthContext';
import { Button, Card, EmptyState, ErrorNotice, Input, Modal, PriorityBadge, Skeleton, Table, useToast, type Column } from '../../components/ui';
import { formatMinutes } from '../../lib/format';
import { qk } from '../../lib/queryKeys';
import { slaSchema } from '../../lib/schemas';

export default function SlaPage() {
  const t = useTenantId();
  const qc = useQueryClient();
  const toast = useToast();
  const [editing, setEditing] = useState<SlaPolicy | null>(null);
  const q = useQuery({ queryKey: qk.sla(t), queryFn: slaApi.list });
  const columns: Column<SlaPolicy>[] = [
    { key: 'p', header: 'Priority', render: (p) => <PriorityBadge priority={p.priority} /> },
    { key: 'fr', header: 'First response', render: (p) => formatMinutes(p.firstResponseMinutes) },
    { key: 'res', header: 'Resolution', render: (p) => formatMinutes(p.resolutionMinutes) },
    { key: 'esc', header: 'Escalate at', render: (p) => (p.escalationAfterPercent ?? []).map((x) => `${x}%`).join(', ') || '—' },
    { key: 'a', header: 'Actions', render: (p) => <Button size="sm" variant="secondary" aria-label={`Edit ${p.priority} policy`} onClick={() => setEditing(p)}>Edit</Button> },
  ];
  return (
    <Card title="SLA policies">
      <p className="muted small">Business hours come from the organization settings.</p>
      {q.isLoading && <Skeleton lines={4} label="Loading SLA policies" />}
      {q.isError && <ErrorNotice message={describeError(q.error)} onRetry={() => void q.refetch()} />}
      {q.data && q.data.length === 0 && <EmptyState title="No SLA policies" />}
      {q.data && q.data.length > 0 && <Table caption="SLA policies" columns={columns} rows={q.data} rowKey={(p) => p.id} />}
      {editing && <SlaModal policy={editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); void qc.invalidateQueries({ queryKey: qk.sla(t) }); toast.push('success', 'SLA policy saved'); }} />}
    </Card>
  );
}

function SlaModal({ policy, onClose, onDone }: { policy: SlaPolicy; onClose: () => void; onDone: () => void }) {
  const [formError, setFormError] = useState<string | null>(null);
  const { register, handleSubmit, formState: { errors } } = useForm<{ firstResponseMinutes: number; resolutionMinutes: number; escalationAfterPercent: string }>({
    resolver: zodResolver(slaSchema),
    defaultValues: { firstResponseMinutes: policy.firstResponseMinutes, resolutionMinutes: policy.resolutionMinutes, escalationAfterPercent: (policy.escalationAfterPercent ?? []).join(', ') },
  });
  const m = useMutation({
    mutationFn: (v: { firstResponseMinutes: number; resolutionMinutes: number; escalationAfterPercent: string }) => slaApi.update(policy.id, {
      priority: policy.priority, firstResponseMinutes: v.firstResponseMinutes, resolutionMinutes: v.resolutionMinutes,
      escalationAfterPercent: v.escalationAfterPercent.split(',').map((x) => x.trim()).filter(Boolean).map(Number),
    }),
    onSuccess: onDone,
    onError: (e) => setFormError(describeError(e)),
  });
  return (
    <Modal open title={`Edit ${policy.priority} SLA policy`} onClose={onClose}>
      <form onSubmit={handleSubmit((v) => { setFormError(null); m.mutate(v); })} noValidate aria-label="SLA policy form">
        <Input label="First response (minutes)" type="number" min={1} required data-autofocus {...register('firstResponseMinutes')} error={errors.firstResponseMinutes?.message} />
        <Input label="Resolution (minutes)" type="number" min={1} required {...register('resolutionMinutes')} error={errors.resolutionMinutes?.message} />
        <Input label="Escalate after % of time elapsed" hint="Comma separated, e.g. 50, 80" {...register('escalationAfterPercent')} error={errors.escalationAfterPercent?.message} />
        {formError && <p className="notice notice-error" role="alert">{formError}</p>}
        <div className="modal-footer">
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button type="submit" loading={m.isPending} disabled={m.isPending}>Save</Button>
        </div>
      </form>
    </Modal>
  );
}
