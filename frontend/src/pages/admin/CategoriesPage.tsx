import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { categoryApi } from '../../api/endpoints';
import { describeError } from '../../api/client';
import { PRIORITIES, TICKET_TYPES, type Category, type Priority, type TicketType } from '../../api/types';
import { useTenantId } from '../../auth/AuthContext';
import { Button, Card, ConfirmDialog, EmptyState, ErrorNotice, Input, Modal, PriorityBadge, Select, Skeleton, Table, useToast, type Column } from '../../components/ui';
import { humanize } from '../../lib/format';
import { qk } from '../../lib/queryKeys';
import { categorySchema, type CategoryForm } from '../../lib/schemas';

export default function CategoriesPage() {
  const t = useTenantId();
  const qc = useQueryClient();
  const toast = useToast();
  const [editing, setEditing] = useState<Category | 'new' | null>(null);
  const [deleting, setDeleting] = useState<Category | null>(null);
  const q = useQuery({ queryKey: qk.categories(t), queryFn: categoryApi.list });
  const refresh = (msg: string) => { void qc.invalidateQueries({ queryKey: qk.categories(t) }); toast.push('success', msg); };
  const del = useMutation({
    mutationFn: (c: Category) => categoryApi.remove(c.id),
    onSuccess: () => { setDeleting(null); refresh('Category deleted'); },
    onError: (e) => { setDeleting(null); toast.push('error', describeError(e)); },
  });

  const columns: Column<Category>[] = [
    { key: 'name', header: 'Name', render: (c) => c.name },
    { key: 'type', header: 'Type', render: (c) => humanize(c.type) },
    { key: 'prio', header: 'Default priority', render: (c) => <PriorityBadge priority={c.defaultPriority} /> },
    { key: 'actions', header: 'Actions', render: (c) => (
      <span className="btn-row">
        <Button size="sm" variant="secondary" onClick={() => setEditing(c)} aria-label={`Edit ${c.name}`}>Edit</Button>
        <Button size="sm" variant="danger" onClick={() => setDeleting(c)} aria-label={`Delete ${c.name}`}>Delete</Button>
      </span>
    ) },
  ];

  return (
    <Card title="Ticket categories" actions={<Button onClick={() => setEditing('new')}>Add category</Button>}>
      {q.isLoading && <Skeleton lines={4} label="Loading categories" />}
      {q.isError && <ErrorNotice message={describeError(q.error)} onRetry={() => void q.refetch()} />}
      {q.data && q.data.length === 0 && <EmptyState title="No categories yet" />}
      {q.data && q.data.length > 0 && <Table caption="Ticket categories" columns={columns} rows={q.data} rowKey={(c) => c.id} />}
      {editing && <CategoryModal category={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onDone={() => { const n = editing === 'new'; setEditing(null); refresh(n ? 'Category created' : 'Category updated'); }} />}
      <ConfirmDialog open={!!deleting} danger title="Delete category?" message={<p>Delete <strong>{deleting?.name}</strong>? Existing tickets keep their history.</p>}
        confirmLabel="Delete" busy={del.isPending} onConfirm={() => deleting && del.mutate(deleting)} onCancel={() => setDeleting(null)} />
    </Card>
  );
}

function CategoryModal({ category, onClose, onDone }: { category: Category | null; onClose: () => void; onDone: () => void }) {
  const [formError, setFormError] = useState<string | null>(null);
  const { register, handleSubmit, formState: { errors } } = useForm<CategoryForm>({
    resolver: zodResolver(categorySchema),
    defaultValues: { name: category?.name ?? '', type: category?.type ?? 'SUPPORT', defaultPriority: category?.defaultPriority ?? 'MEDIUM' },
  });
  const m = useMutation({
    mutationFn: (v: CategoryForm) => {
      const body = { name: v.name.trim(), type: v.type as TicketType, defaultPriority: v.defaultPriority as Priority };
      return category ? categoryApi.update(category.id, body) : categoryApi.create(body);
    },
    onSuccess: onDone,
    onError: (e) => setFormError(describeError(e)),
  });
  return (
    <Modal open title={category ? 'Edit category' : 'Add category'} onClose={onClose}>
      <form onSubmit={handleSubmit((v) => { setFormError(null); m.mutate(v); })} noValidate aria-label="Category form">
        <Input label="Name" required data-autofocus {...register('name')} error={errors.name?.message} />
        <Select label="Type" {...register('type')}>{TICKET_TYPES.map((x) => <option key={x} value={x}>{humanize(x)}</option>)}</Select>
        <Select label="Default priority" {...register('defaultPriority')}>{PRIORITIES.map((x) => <option key={x} value={x}>{humanize(x)}</option>)}</Select>
        {formError && <p className="notice notice-error" role="alert">{formError}</p>}
        <div className="modal-footer">
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button type="submit" loading={m.isPending} disabled={m.isPending}>Save</Button>
        </div>
      </form>
    </Modal>
  );
}
