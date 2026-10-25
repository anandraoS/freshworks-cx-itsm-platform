import { AbstractControl } from '@angular/forms';
import {
  buildArticleForm, buildCategoryForm, buildCommentForm, buildLoginForm, buildSettingsForm, buildSlaForm, buildTicketForm,
  buildTransitionForm, buildUserCreateForm, buildWorkflowForm, defaultWorkflowForm, parsePercents, parseTags, toWorkflowInput, fromWorkflow,
} from './form-schemas';
import { firstError } from './validators';

const msg = (c: AbstractControl) => firstError(c);

describe('form validation (mirrors the Zod rules of the React app)', () => {
  it('login: required tenant slug, valid slug chars, email, password', () => {
    const f = buildLoginForm();
    expect(f.valid).toBeFalse();
    expect(msg(f.controls.tenantSlug)).toBe('Organization is required');
    f.controls.tenantSlug.setValue('bad slug!');
    expect(msg(f.controls.tenantSlug)).toBe('Use letters, numbers and dashes only');
    f.controls.email.setValue('nope');
    expect(msg(f.controls.email)).toBe('Enter a valid email');
    expect(msg(f.controls.password)).toBe('Password is required');
    f.setValue({ tenantSlug: 'acme-retail', email: 'agent1@acme.test', password: 'x' });
    expect(f.valid).toBeTrue();
  });

  it('ticket: title 5..200, description >= 10, at most 10 tags, trims before checking', () => {
    const f = buildTicketForm();
    f.controls.title.setValue('  ab  ');
    expect(msg(f.controls.title)).toBe('Title must be at least 5 characters');
    f.controls.title.setValue('x'.repeat(201));
    expect(msg(f.controls.title)).toBe('Title must be at most 200 characters');
    f.controls.description.setValue('short');
    expect(msg(f.controls.description)).toBe('Please describe the issue (at least 10 characters)');
    f.controls.tags.setValue(Array.from({ length: 11 }, (_, i) => `t${i}`).join(','));
    expect(msg(f.controls.tags)).toBe('At most 10 tags');
    f.patchValue({ title: 'Valid title', description: 'A long enough description', tags: 'a, b, a' });
    expect(f.valid).toBeTrue();
    expect(parseTags('a, b, a, ,c')).toEqual(['a', 'b', 'c']);
  });

  it('comment and transition reason', () => {
    const c = buildCommentForm();
    expect(msg(c.controls.body)).toBe('Comment cannot be empty');
    c.controls.body.setValue('   ');
    expect(c.valid).toBeFalse();
    c.controls.body.setValue('ok');
    expect(c.valid).toBeTrue();
    const t = buildTransitionForm();
    t.controls.reason.setValue('ab');
    expect(msg(t.controls.reason)).toBe('Please give a reason (at least 3 characters)');
    t.controls.reason.setValue('x'.repeat(501));
    expect(msg(t.controls.reason)).toBe('Reason is too long');
    t.controls.reason.setValue('because');
    expect(t.valid).toBeTrue();
  });

  it('article', () => {
    const f = buildArticleForm();
    f.patchValue({ title: 'ab', body: 'short' });
    expect(msg(f.controls.title)).toBe('Title must be at least 3 characters');
    expect(msg(f.controls.body)).toBe('Body must be at least 10 characters');
    f.controls.summary.setValue('s'.repeat(501));
    expect(msg(f.controls.summary)).toBe('Summary is too long');
    f.patchValue({ title: 'How to reset', body: 'Go to settings and press reset.', summary: 'ok' });
    expect(f.valid).toBeTrue();
  });

  it('user create: password >= 10 chars, valid email, name >= 2', () => {
    const f = buildUserCreateForm();
    f.patchValue({ email: 'x', displayName: 'A', password: 'short' });
    expect(msg(f.controls.email)).toBe('Enter a valid email');
    expect(msg(f.controls.displayName)).toBe('Name is required');
    expect(msg(f.controls.password)).toBe('Password must be at least 10 characters');
    f.patchValue({ email: 'new@acme.test', displayName: 'New User', password: 'longenough1' });
    expect(f.valid).toBeTrue();
  });

  it('settings: HH:MM, end after start, at least one day, https webhook', () => {
    const f = buildSettingsForm();
    f.patchValue({ name: 'Acme', timezone: 'UTC', businessHoursStart: '09:00', businessHoursEnd: '17:00', businessDays: ['MONDAY'], defaultLanguage: 'en' });
    expect(f.valid).toBeTrue();
    f.controls.businessHoursEnd.setValue('08:00');
    expect(msg(f.controls.businessHoursEnd)).toBe('End must be after start');
    expect(f.valid).toBeFalse();
    f.controls.businessHoursEnd.setValue('18:00');
    expect(f.controls.businessHoursEnd.valid).toBeTrue();
    f.controls.businessDays.setValue([]);
    expect(msg(f.controls.businessDays)).toBe('Select at least one business day');
    f.controls.businessDays.setValue(['MONDAY']);
    f.controls.slackWebhookUrl.setValue('http://insecure.example');
    expect(msg(f.controls.slackWebhookUrl)).toBe('Must start with https://');
    f.controls.slackWebhookUrl.setValue('https://example.invalid/x');
    expect(f.valid).toBeTrue();
  });

  it('sla: minutes >= 1, resolution >= first response, percent list 1..100', () => {
    const f = buildSlaForm({ firstResponseMinutes: 60, resolutionMinutes: 480, escalationAfterPercent: [50, 80] });
    expect(f.valid).toBeTrue();
    f.controls.resolutionMinutes.setValue(30);
    expect(msg(f.controls.resolutionMinutes)).toBe('Resolution must be >= first response');
    f.controls.resolutionMinutes.setValue(600);
    expect(f.controls.resolutionMinutes.valid).toBeTrue();
    f.controls.firstResponseMinutes.setValue(0);
    expect(msg(f.controls.firstResponseMinutes)).toBe('Must be at least 1');
    f.controls.firstResponseMinutes.setValue(10);
    f.controls.escalationAfterPercent.setValue('50, 150');
    expect(msg(f.controls.escalationAfterPercent)).toBe('Comma-separated percentages between 1 and 100');
    expect(parsePercents('50, 80')).toEqual([50, 80]);
  });

  it('category', () => {
    const f = buildCategoryForm();
    expect(msg(f.controls.name)).toBe('Name is required');
    f.controls.name.setValue('Hardware');
    expect(f.valid).toBeTrue();
  });

  it('workflow: transition to itself is rejected; payload mapping round-trips', () => {
    const f = buildWorkflowForm(defaultWorkflowForm());
    expect(f.valid).toBeTrue();
    f.controls.transitions.at(0).patchValue({ from: 'OPEN', to: 'OPEN' });
    expect(f.valid).toBeFalse();
    expect(f.controls.transitions.errors?.['same']?.message).toBe('A transition cannot go to the same status');

    const input = toWorkflowInput({
      name: ' Flow ',
      rules: [{ name: 'Urgent', when: [{ field: 'priority', op: 'EQ', value: 'URGENT' }], then: [{ action: 'SET_PRIORITY', param: 'HIGH' }, { action: 'NOTIFY', param: '' }] }],
      transitions: [{ from: 'NEW', to: 'OPEN' }],
    });
    expect(input.name).toBe('Flow');
    expect(input.rules[0].then[0]).toEqual({ action: 'SET_PRIORITY', params: { priority: 'HIGH' } });
    expect(input.rules[0].then[1].params).toEqual({});
    const back = fromWorkflow({ id: '1', version: 1, active: false, createdAt: '', ...input });
    expect(back.rules[0].then[0].param).toBe('HIGH');
  });
});
