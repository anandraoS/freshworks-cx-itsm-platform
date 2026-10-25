import { useEffect, useState } from 'react';
import { useFieldArray, useForm, useWatch, type Control, type UseFormRegister } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { workflowApi } from '../../api/endpoints';
import { describeError } from '../../api/client';
import {
  DEFAULT_TRANSITIONS, STATUSES, WORKFLOW_ACTIONS, WORKFLOW_OPS,
  type Workflow, type WorkflowAction, type WorkflowCondition, type WorkflowInput, type TicketStatus,
} from '../../api/types';
import { useAuth, useTenantId } from '../../auth/AuthContext';
import { atLeast } from '../../auth/roles';
import { Badge, Button, Card, EmptyState, ErrorNotice, Input, Select, Skeleton, useToast } from '../../components/ui';
import { humanize, formatDateTime } from '../../lib/format';
import { qk } from '../../lib/queryKeys';
import { workflowSchema, type WorkflowForm } from '../../lib/schemas';

/** Each action takes one named parameter in the constrained model. */
const PARAM_KEY: Record<WorkflowAction['action'], string> = {
  ASSIGN_TO_ROLE_LEAST_LOADED: 'role', SET_PRIORITY: 'priority', ESCALATE: 'level', NOTIFY: 'target', ADD_TAG: 'tag',
};

export function toInput(f: WorkflowForm): WorkflowInput {
  return {
    name: f.name.trim(),
    rules: f.rules.map((r) => ({
      name: r.name.trim(),
      when: r.when.map((c) => ({ field: c.field.trim(), op: c.op as WorkflowCondition['op'], value: c.value.trim() })),
      then: r.then.map((a) => {
        const action = a.action as WorkflowAction['action'];
        return { action, params: a.param.trim() ? { [PARAM_KEY[action]]: a.param.trim() } : {} };
      }),
    })),
    transitions: f.transitions.map((x) => ({ from: x.from as TicketStatus, to: x.to as TicketStatus })),
  };
}

export function fromWorkflow(w: Workflow): WorkflowForm {
  return {
    name: w.name,
    rules: w.rules.map((r) => ({
      name: r.name,
      when: r.when.map((c) => ({ field: c.field, op: c.op, value: String(c.value) })),
      then: r.then.map((a) => ({ action: a.action, param: Object.values(a.params ?? {})[0] ?? '' })),
    })),
    transitions: w.transitions.map((x) => ({ from: x.from, to: x.to })),
  };
}

const defaultForm = (): WorkflowForm => ({
  name: 'Default workflow',
  rules: [],
  transitions: Object.entries(DEFAULT_TRANSITIONS).flatMap(([from, tos]) => tos.map((to) => ({ from, to }))),
});

export default function WorkflowsPage() {
  const t = useTenantId();
  const { user } = useAuth();
  const canEdit = atLeast(user, 'ADMIN');
  const qc = useQueryClient();
  const toast = useToast();
  const q = useQuery({ queryKey: qk.workflows(t), queryFn: workflowApi.list });
  const [loaded, setLoaded] = useState<WorkflowForm | null>(null);
  const [editorKey, setEditorKey] = useState(0);
  const activate = useMutation({
    mutationFn: (id: string) => workflowApi.activate(id),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: qk.workflows(t) }); toast.push('success', 'Workflow activated'); },
    onError: (e) => toast.push('error', describeError(e)),
  });

  return (
    <div className="stack">
      <Card title="Workflow versions">
        {q.isLoading && <Skeleton lines={4} label="Loading workflows" />}
        {q.isError && <ErrorNotice message={describeError(q.error)} onRetry={() => void q.refetch()} />}
        {q.data && q.data.length === 0 && <EmptyState title="No workflows yet" description={canEdit ? 'Create the first version below.' : undefined} />}
        <ul className="plain-list">
          {q.data?.map((w) => (
            <li key={w.id} className="wf-row">
              <span><strong>{w.name}</strong> · v{w.version} · {w.rules.length} rules · {w.transitions.length} transitions · <span className="muted small">{formatDateTime(w.createdAt)}</span> {w.active && <Badge tone="success">Active</Badge>}</span>
              {canEdit && (
                <span className="btn-row">
                  <Button size="sm" variant="secondary" onClick={() => { setLoaded(fromWorkflow(w)); setEditorKey((k) => k + 1); }}>Use as template</Button>
                  {!w.active && <Button size="sm" onClick={() => activate.mutate(w.id)} loading={activate.isPending && activate.variables === w.id}>Activate</Button>}
                </span>
              )}
            </li>
          ))}
        </ul>
      </Card>
      {canEdit ? <WorkflowEditor key={editorKey} initial={loaded ?? defaultForm()} /> : <p className="muted">Workflow editing requires the ADMIN role.</p>}
    </div>
  );
}

function WorkflowEditor({ initial }: { initial: WorkflowForm }) {
  const t = useTenantId();
  const qc = useQueryClient();
  const toast = useToast();
  const [validation, setValidation] = useState<{ valid: boolean; errors: string[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { register, control, handleSubmit, getValues, formState: { errors } } = useForm<WorkflowForm>({ resolver: zodResolver(workflowSchema), defaultValues: initial });
  const rules = useFieldArray({ control, name: 'rules' });
  const transitions = useFieldArray({ control, name: 'transitions' });

  // Any edit invalidates the previous server validation: must validate again before saving.
  const watched = useWatch({ control });
  const snapshot = JSON.stringify(watched);
  useEffect(() => { setValidation(null); }, [snapshot]);

  const validate = useMutation({
    mutationFn: (f: WorkflowForm) => workflowApi.validate(toInput(f)),
    onSuccess: (r) => { setValidation({ valid: r.valid, errors: r.errors ?? [] }); setError(null); },
    onError: (e) => setError(describeError(e)),
  });
  const save = useMutation({
    mutationFn: () => workflowApi.create(toInput(getValues())),
    onSuccess: (w) => { void qc.invalidateQueries({ queryKey: qk.workflows(t) }); toast.push('success', `Saved as version ${w.version}. Activate it from the list above.`); setValidation(null); },
    onError: (e) => setError(describeError(e)),
  });

  return (
    <Card title="Workflow editor">
      <form onSubmit={handleSubmit((f) => { setError(null); validate.mutate(f); })} noValidate aria-label="Workflow editor">
        <Input label="Workflow name" required {...register('name')} error={errors.name?.message} />

        <h3>Automation rules</h3>
        {rules.fields.length === 0 && <p className="muted">No rules. Add a rule to automate assignment, priority, escalation, notifications or tagging.</p>}
        {rules.fields.map((f, i) => (
          <RuleEditor key={f.id} index={i} control={control} register={register} onRemove={() => rules.remove(i)} errors={errors} />
        ))}
        <Button variant="secondary" size="sm" onClick={() => rules.append({ name: '', when: [{ field: 'priority', op: 'EQ', value: '' }], then: [{ action: 'NOTIFY', param: '' }] })}>Add rule</Button>

        <h3>Allowed transitions</h3>
        {transitions.fields.map((f, i) => (
          <div key={f.id} className="row-inline">
            <Select label={`Transition ${i + 1} from`} {...register(`transitions.${i}.from` as const)}>{STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}</Select>
            <span aria-hidden="true">→</span>
            <Select label={`Transition ${i + 1} to`} {...register(`transitions.${i}.to` as const)}>{STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}</Select>
            <Button size="sm" variant="ghost" aria-label={`Remove transition ${i + 1}`} onClick={() => transitions.remove(i)}>Remove</Button>
          </div>
        ))}
        {errors.transitions?.root?.message && <p className="field-msg" role="alert">{errors.transitions.root.message}</p>}
        {errors.transitions?.message && <p className="field-msg" role="alert">{errors.transitions.message}</p>}
        <Button variant="secondary" size="sm" onClick={() => transitions.append({ from: 'OPEN', to: 'RESOLVED' })}>Add transition</Button>

        {error && <p className="notice notice-error" role="alert">{error}</p>}
        {validation && (validation.valid
          ? <p className="notice notice-success" role="status">Validation passed. You can save this as a new version.</p>
          : <div className="notice notice-error" role="alert"><strong>Validation failed</strong><ul>{validation.errors.map((e) => <li key={e}>{e}</li>)}</ul></div>)}

        <div className="form-actions">
          <Button type="submit" variant="secondary" loading={validate.isPending} disabled={validate.isPending}>1. Validate</Button>
          <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!validation?.valid || save.isPending}>2. Save as new version</Button>
        </div>
      </form>
    </Card>
  );
}

function RuleEditor({ index, control, register, onRemove, errors }: { index: number; control: Control<WorkflowForm>; register: UseFormRegister<WorkflowForm>; onRemove: () => void; errors: ReturnType<typeof useForm<WorkflowForm>>['formState']['errors'] }) {
  const when = useFieldArray({ control, name: `rules.${index}.when` });
  const then = useFieldArray({ control, name: `rules.${index}.then` });
  const re = errors.rules?.[index];
  return (
    <fieldset className="fieldset rule">
      <legend>Rule {index + 1}</legend>
      <Input label="Rule name" {...register(`rules.${index}.name` as const)} error={re?.name?.message} />
      <p className="small"><strong>When</strong> (all conditions match)</p>
      {when.fields.map((f, j) => (
        <div key={f.id} className="row-inline">
          <Input label={`Field ${j + 1}`} placeholder="priority" {...register(`rules.${index}.when.${j}.field` as const)} error={re?.when?.[j]?.field?.message} />
          <Select label={`Operator ${j + 1}`} {...register(`rules.${index}.when.${j}.op` as const)}>{WORKFLOW_OPS.map((o) => <option key={o} value={o}>{o}</option>)}</Select>
          <Input label={`Value ${j + 1}`} {...register(`rules.${index}.when.${j}.value` as const)} error={re?.when?.[j]?.value?.message} />
          <Button size="sm" variant="ghost" aria-label={`Remove condition ${j + 1} of rule ${index + 1}`} onClick={() => when.remove(j)}>Remove</Button>
        </div>
      ))}
      <Button size="sm" variant="secondary" onClick={() => when.append({ field: '', op: 'EQ', value: '' })}>Add condition</Button>
      <p className="small"><strong>Then</strong></p>
      {then.fields.map((f, j) => (
        <div key={f.id} className="row-inline">
          <Select label={`Action ${j + 1}`} {...register(`rules.${index}.then.${j}.action` as const)}>{WORKFLOW_ACTIONS.map((a) => <option key={a} value={a}>{humanize(a)}</option>)}</Select>
          <Input label={`Parameter ${j + 1}`} {...register(`rules.${index}.then.${j}.param` as const)} />
          <Button size="sm" variant="ghost" aria-label={`Remove action ${j + 1} of rule ${index + 1}`} onClick={() => then.remove(j)}>Remove</Button>
        </div>
      ))}
      <Button size="sm" variant="secondary" onClick={() => then.append({ action: 'NOTIFY', param: '' })}>Add action</Button>
      {re?.when?.message && <p className="field-msg" role="alert">{re.when.message}</p>}
      {re?.then?.message && <p className="field-msg" role="alert">{re.then.message}</p>}
      <div><Button size="sm" variant="danger" onClick={onRemove}>Remove rule {index + 1}</Button></div>
    </fieldset>
  );
}
