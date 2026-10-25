import { AbstractControl, FormArray, FormControl, FormGroup, ValidationErrors, ValidatorFn } from '@angular/forms';
import { DEFAULT_TRANSITIONS, PRIORITIES, ROLES, SEVERITIES, TICKET_TYPES } from './models';
import type { Priority, Role, Severity, TicketStatus, TicketType, Workflow, WorkflowInput } from './models';
import { custom, emailFormat, matches, maxLen, minItems, minLen, minNumber, rawMinLen, required } from './validators';
import type { WorkflowAction, WorkflowCondition } from './models';

/**
 * Typed reactive-form factories. Each mirrors the Zod schema of the same name in frontend/src/lib/schemas.ts
 * (same limits, same messages) so both UIs behave identically.
 */
const nn = <T>(value: T, validators: ValidatorFn[] = []) => new FormControl<T>(value, { nonNullable: true, validators });

export function parseTags(s: string): string[] {
  return Array.from(new Set(s.split(',').map((t) => t.trim()).filter(Boolean)));
}

export function buildLoginForm() {
  return new FormGroup({
    tenantSlug: nn('', [required('Organization is required'), matches(/^[a-z0-9-]+$/i, 'Use letters, numbers and dashes only')]),
    email: nn('', [required('Email is required'), emailFormat('Enter a valid email')]),
    password: nn('', [(c) => (String(c.value ?? '').length > 0 ? null : { required: { message: 'Password is required' } })]),
  });
}

export function buildTicketForm() {
  return new FormGroup({
    type: nn<TicketType>('SUPPORT'),
    title: nn('', [minLen(5, 'Title must be at least 5 characters'), maxLen(200, 'Title must be at most 200 characters')]),
    description: nn('', [minLen(10, 'Please describe the issue (at least 10 characters)'), maxLen(10000, 'Description is too long')]),
    priority: nn<Priority>('MEDIUM', [custom('enum', 'Select a priority', (v) => (PRIORITIES as string[]).includes(v))]),
    severity: nn<Severity>('MINOR', [custom('enum', 'Select a severity', (v) => (SEVERITIES as string[]).includes(v))]),
    categoryId: nn(''),
    tags: nn('', [
      maxLen(200, 'Tags are too long'),
      custom('tags', 'At most 10 tags', (v) => parseTagsLoose(v) <= 10),
    ]),
  });
}
const parseTagsLoose = (s: string) => s.split(',').map((t) => t.trim()).filter(Boolean).length;

export function buildCommentForm() {
  return new FormGroup({ body: nn('', [minLen(1, 'Comment cannot be empty'), maxLen(5000, 'Comment is too long')]) });
}

export function buildTransitionForm() {
  return new FormGroup({ reason: nn('', [minLen(3, 'Please give a reason (at least 3 characters)'), maxLen(500, 'Reason is too long')]) });
}

export function buildArticleForm() {
  return new FormGroup({
    title: nn('', [minLen(3, 'Title must be at least 3 characters'), maxLen(200, 'Title is too long')]),
    summary: nn('', [maxLen(500, 'Summary is too long')]),
    body: nn('', [minLen(10, 'Body must be at least 10 characters')]),
    categoryId: nn(''),
    visibility: nn<'PUBLIC' | 'INTERNAL'>('PUBLIC'),
  });
}

export function buildUserCreateForm() {
  return new FormGroup({
    email: nn('', [emailFormat('Enter a valid email'), required('Enter a valid email')]),
    displayName: nn('', [minLen(2, 'Name is required'), maxLen(100, 'Name is too long')]),
    password: nn('', [rawMinLen(10, 'Password must be at least 10 characters')]),
    role: nn<Role>('AGENT', [custom('enum', 'Select a role', (v) => (ROLES as string[]).includes(v))]),
  });
}


/** Sets/clears a cross-field error on a child control without clobbering its own validator errors. */
function setCrossError(ctrl: AbstractControl | null, key: string, message: string | null): void {
  if (!ctrl) return;
  const { [key]: _old, ...rest } = ctrl.errors ?? {};
  void _old;
  const next = message ? { ...rest, [key]: { message } } : rest;
  ctrl.setErrors(Object.keys(next).length ? next : null);
}

const timeAfter: ValidatorFn = (g: AbstractControl): ValidationErrors | null => {
  const s = g.get('businessHoursStart')?.value as string, e = g.get('businessHoursEnd')?.value as string;
  const bad = !!s && !!e && /^\d{2}:\d{2}$/.test(s) && /^\d{2}:\d{2}$/.test(e) && !(s < e);
  setCrossError(g.get('businessHoursEnd'), 'order', bad ? 'End must be after start' : null);
  return bad ? { order: { message: 'End must be after start' } } : null;
};

export function buildSettingsForm() {
  const f = new FormGroup({
    name: nn('', [minLen(2, 'Organization name is required'), maxLen(120, 'Name is too long')]),
    timezone: nn('', [required('Timezone is required')]),
    businessHoursStart: nn('', [matches(/^\d{2}:\d{2}$/, 'Use HH:MM'), required('Use HH:MM')]),
    businessHoursEnd: nn('', [matches(/^\d{2}:\d{2}$/, 'Use HH:MM'), required('Use HH:MM')]),
    businessDays: nn<string[]>([], [minItems(1, 'Select at least one business day')]),
    defaultLanguage: nn('', [minLen(2, 'Language is required')]),
    emailProvider: nn('', [maxLen(50, 'Too long')]),
    slackWebhookUrl: nn('', [maxLen(300, 'Too long'), custom('https', 'Must start with https://', (v) => v === '' || /^https:\/\//.test(v))]),
    aiProvider: nn('', [maxLen(50, 'Too long')]),
  });
  f.addValidators(timeAfter);
  return f;
}

export function buildCategoryForm(init?: { name: string; type: TicketType; defaultPriority: Priority }) {
  return new FormGroup({
    name: nn(init?.name ?? '', [minLen(2, 'Name is required'), maxLen(80, 'Name is too long')]),
    type: nn<TicketType>(init?.type ?? 'SUPPORT', [custom('enum', 'Select a type', (v) => (TICKET_TYPES as string[]).includes(v))]),
    defaultPriority: nn<Priority>(init?.defaultPriority ?? 'MEDIUM'),
  });
}

export function parsePercents(s: string): number[] {
  return s.split(',').map((x) => x.trim()).filter(Boolean).map(Number);
}

const resolutionAfterFirst: ValidatorFn = (g) => {
  const a = Number(g.get('firstResponseMinutes')?.value), b = Number(g.get('resolutionMinutes')?.value);
  const bad = Number.isFinite(a) && Number.isFinite(b) && b < a;
  setCrossError(g.get('resolutionMinutes'), 'order', bad ? 'Resolution must be >= first response' : null);
  return bad ? { order: { message: 'Resolution must be >= first response' } } : null;
};

export function buildSlaForm(init?: { firstResponseMinutes: number; resolutionMinutes: number; escalationAfterPercent: number[] }) {
  const f = new FormGroup({
    firstResponseMinutes: nn<number | string>(init?.firstResponseMinutes ?? 60, [minNumber(1, 'Must be at least 1')]),
    resolutionMinutes: nn<number | string>(init?.resolutionMinutes ?? 480, [minNumber(1, 'Must be at least 1')]),
    escalationAfterPercent: nn((init?.escalationAfterPercent ?? []).join(', '), [
      custom('percent', 'Comma-separated percentages between 1 and 100',
        (s) => s.split(',').map((x) => x.trim()).filter(Boolean).every((x) => /^\d+$/.test(x) && +x > 0 && +x <= 100)),
    ]),
  });
  f.addValidators(resolutionAfterFirst);
  return f;
}

/** Each workflow action takes one named parameter in the constrained model. */
export const PARAM_KEY: Record<WorkflowAction['action'], string> = {
  ASSIGN_TO_ROLE_LEAST_LOADED: 'role', SET_PRIORITY: 'priority', ESCALATE: 'level', NOTIFY: 'target', ADD_TAG: 'tag',
};

export const buildCondition = (c: { field: string; op: string; value: string } = { field: '', op: 'EQ', value: '' }) =>
  new FormGroup({ field: nn(c.field, [required('Field required')]), op: nn(c.op), value: nn(c.value, [required('Value required')]) });
export const buildAction = (a: { action: string; param: string } = { action: 'NOTIFY', param: '' }) =>
  new FormGroup({ action: nn(a.action), param: nn(a.param) });
export const buildRule = (r?: { name: string; when: { field: string; op: string; value: string }[]; then: { action: string; param: string }[] }) =>
  new FormGroup({
    name: nn(r?.name ?? '', [required('Rule name is required')]),
    when: new FormArray((r?.when ?? [{ field: 'priority', op: 'EQ', value: '' }]).map((c) => buildCondition(c)), [(c) => ((c as FormArray).length >= 1 ? null : { min: { message: 'At least one condition' } })]),
    then: new FormArray((r?.then ?? [{ action: 'NOTIFY', param: '' }]).map((a) => buildAction(a)), [(c) => ((c as FormArray).length >= 1 ? null : { min: { message: 'At least one action' } })]),
  });
export const buildTransition = (t: { from: string; to: string } = { from: 'OPEN', to: 'RESOLVED' }) =>
  new FormGroup({ from: nn(t.from), to: nn(t.to) });

export type WorkflowFormValue = {
  name: string;
  rules: { name: string; when: { field: string; op: string; value: string }[]; then: { action: string; param: string }[] }[];
  transitions: { from: string; to: string }[];
};

export function buildWorkflowForm(init: WorkflowFormValue) {
  return new FormGroup({
    name: nn(init.name, [minLen(2, 'Name is required'), maxLen(100, 'Name is too long')]),
    rules: new FormArray(init.rules.map((r) => buildRule(r))),
    transitions: new FormArray(init.transitions.map((t) => buildTransition(t)), [
      (c) => ((c as FormArray).controls.every((g) => g.get('from')?.value !== g.get('to')?.value) ? null : { same: { message: 'A transition cannot go to the same status' } }),
    ]),
  });
}

export function defaultWorkflowForm(): WorkflowFormValue {
  return {
    name: 'Default workflow',
    rules: [],
    transitions: Object.entries(DEFAULT_TRANSITIONS).flatMap(([from, tos]) => tos.map((to) => ({ from, to }))),
  };
}

export function toWorkflowInput(f: WorkflowFormValue): WorkflowInput {
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

export function fromWorkflow(w: Workflow): WorkflowFormValue {
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
