import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { userApi } from '../../api/endpoints';
import { describeError } from '../../api/client';
import { ROLES, type ManagedUser, type Role } from '../../api/types';
import { useTenantId } from '../../auth/AuthContext';
import { Badge, Button, Card, EmptyState, ErrorNotice, Input, Modal, Select, Skeleton, Table, useToast, type Column } from '../../components/ui';
import { applyFieldErrors } from '../../lib/formErrors';
import { qk } from '../../lib/queryKeys';
import { userCreateSchema, type UserCreateForm } from '../../lib/schemas';

export default function UsersPage() {
  const t = useTenantId();
  const qc = useQueryClient();
  const toast = useToast();
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<ManagedUser | null>(null);
  const q = useQuery({ queryKey: qk.users(t), queryFn: userApi.list });
  const done = (msg: string) => { void qc.invalidateQueries({ queryKey: qk.users(t) }); toast.push('success', msg); };

  const columns: Column<ManagedUser>[] = [
    { key: 'name', header: 'Name', render: (u) => u.displayName },
    { key: 'email', header: 'Email', render: (u) => u.email },
    { key: 'roles', header: 'Roles', render: (u) => u.roles.map((r) => <Badge key={r} tone="info">{r}</Badge>) },
    { key: 'active', header: 'Status', render: (u) => <Badge tone={u.active ? 'success' : 'neutral'}>{u.active ? 'Active' : 'Inactive'}</Badge> },
    { key: 'actions', header: 'Actions', render: (u) => <Button size="sm" variant="secondary" onClick={() => setEditing(u)} aria-label={`Edit ${u.displayName}`}>Edit</Button> },
  ];

  return (
    <Card title="Users & roles" actions={<Button onClick={() => setCreating(true)}>Add user</Button>}>
      {q.isLoading && <Skeleton lines={5} label="Loading users" />}
      {q.isError && <ErrorNotice message={describeError(q.error)} onRetry={() => void q.refetch()} />}
      {q.data && q.data.length === 0 && <EmptyState title="No users" />}
      {q.data && q.data.length > 0 && <Table caption="Users" columns={columns} rows={q.data} rowKey={(u) => u.id} />}
      <CreateUserModal open={creating} onClose={() => setCreating(false)} onDone={() => { setCreating(false); done('User created'); }} />
      {editing && <EditUserModal user={editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); done('User updated'); }} />}
    </Card>
  );
}

function CreateUserModal({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: () => void }) {
  const [formError, setFormError] = useState<string | null>(null);
  const { register, handleSubmit, setError, reset, formState: { errors } } = useForm<UserCreateForm>({ resolver: zodResolver(userCreateSchema), defaultValues: { email: '', displayName: '', password: '', role: 'AGENT' } });
  const m = useMutation({
    mutationFn: (v: UserCreateForm) => userApi.create({ email: v.email.trim(), displayName: v.displayName.trim(), roles: [v.role as Role], password: v.password }),
    onSuccess: () => { reset(); onDone(); },
    onError: (e) => { if (!applyFieldErrors(e, setError, ['email', 'displayName', 'password'])) setFormError(describeError(e)); },
  });
  return (
    <Modal open={open} title="Add user" onClose={onClose}>
      <form onSubmit={handleSubmit((v) => { setFormError(null); m.mutate(v); })} noValidate aria-label="Add user">
        <Input label="Email" type="email" required data-autofocus {...register('email')} error={errors.email?.message} />
        <Input label="Display name" required {...register('displayName')} error={errors.displayName?.message} />
        <Input label="Initial password" type="password" autoComplete="new-password" required {...register('password')} error={errors.password?.message} />
        <Select label="Role" required {...register('role')}>{ROLES.map((r) => <option key={r} value={r}>{r}</option>)}</Select>
        {formError && <p className="notice notice-error" role="alert">{formError}</p>}
        <div className="modal-footer">
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button type="submit" loading={m.isPending} disabled={m.isPending}>Create user</Button>
        </div>
      </form>
    </Modal>
  );
}

function EditUserModal({ user, onClose, onDone }: { user: ManagedUser; onClose: () => void; onDone: () => void }) {
  const [displayName, setDisplayName] = useState(user.displayName);
  const [roles, setRoles] = useState<Role[]>(user.roles);
  const [active, setActive] = useState(user.active);
  const [formError, setFormError] = useState<string | null>(null);
  const m = useMutation({
    mutationFn: () => userApi.update(user.id, { displayName: displayName.trim(), roles, active }),
    onSuccess: onDone,
    onError: (e) => setFormError(describeError(e)),
  });
  const invalid = displayName.trim().length < 2 || roles.length === 0;
  return (
    <Modal open title={`Edit ${user.email}`} onClose={onClose}>
      <Input label="Display name" value={displayName} onChange={(e) => setDisplayName(e.target.value)} data-autofocus
        error={displayName.trim().length < 2 ? 'Name is required' : undefined} />
      <fieldset className="fieldset">
        <legend>Roles</legend>
        <div className="check-row">
          {ROLES.map((r) => (
            <label key={r}><input type="checkbox" checked={roles.includes(r)} onChange={(e) => setRoles(e.target.checked ? [...roles, r] : roles.filter((x) => x !== r))} /> {r}</label>
          ))}
        </div>
        {roles.length === 0 && <p className="field-msg" role="alert">Select at least one role</p>}
      </fieldset>
      <label className="inline-check"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Active</label>
      {formError && <p className="notice notice-error" role="alert">{formError}</p>}
      <div className="modal-footer">
        <Button variant="secondary" onClick={onClose}>Cancel</Button>
        <Button onClick={() => m.mutate()} loading={m.isPending} disabled={invalid || m.isPending}>Save</Button>
      </div>
    </Modal>
  );
}
