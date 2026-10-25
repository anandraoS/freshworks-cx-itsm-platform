import { describe, expect, it } from 'vitest';
import { articleSchema, commentSchema, loginSchema, parseTags, settingsSchema, slaSchema, ticketSchema, transitionSchema, userCreateSchema, workflowSchema } from '../lib/schemas';

const validTicket = { type: 'SUPPORT', title: 'Printer is broken', description: 'The printer on floor 2 is jammed', priority: 'HIGH', severity: 'MAJOR', categoryId: '', tags: 'a, b' };

describe('ticketSchema', () => {
  it('accepts a valid ticket', () => expect(ticketSchema.safeParse(validTicket).success).toBe(true));
  it('rejects short title and description with messages', () => {
    const r = ticketSchema.safeParse({ ...validTicket, title: 'abc', description: 'short' });
    expect(r.success).toBe(false);
    if (!r.success) {
      const msgs = r.error.issues.map((i) => i.message);
      expect(msgs).toContain('Title must be at least 5 characters');
      expect(msgs.some((m) => m.includes('at least 10'))).toBe(true);
    }
  });
  it('rejects unknown priority and more than 10 tags', () => {
    expect(ticketSchema.safeParse({ ...validTicket, priority: 'SUPER' }).success).toBe(false);
    expect(ticketSchema.safeParse({ ...validTicket, tags: Array.from({ length: 11 }, (_, i) => `t${i}`).join(',') }).success).toBe(false);
  });
  it('parseTags trims, drops blanks and de-duplicates', () => expect(parseTags(' a, b ,,a')).toEqual(['a', 'b']));
});

describe('loginSchema', () => {
  it('requires all fields and a valid email', () => {
    expect(loginSchema.safeParse({ tenantSlug: '', email: '', password: '' }).success).toBe(false);
    expect(loginSchema.safeParse({ tenantSlug: 'acme-retail', email: 'nope', password: 'x' }).success).toBe(false);
    expect(loginSchema.safeParse({ tenantSlug: 'acme-retail', email: 'a@b.test', password: 'x' }).success).toBe(true);
  });
  it('rejects slugs with unsafe characters', () => {
    expect(loginSchema.safeParse({ tenantSlug: '../etc', email: 'a@b.test', password: 'x' }).success).toBe(false);
  });
});

describe('other schemas', () => {
  it('comment and transition reason must not be blank', () => {
    expect(commentSchema.safeParse({ body: '   ' }).success).toBe(false);
    expect(transitionSchema.safeParse({ reason: 'ok' }).success).toBe(false);
    expect(transitionSchema.safeParse({ reason: 'Fixed it' }).success).toBe(true);
  });
  it('article requires body length', () => {
    expect(articleSchema.safeParse({ title: 'How to', summary: '', body: 'tiny', categoryId: '', visibility: 'PUBLIC' }).success).toBe(false);
  });
  it('user password min length', () => {
    expect(userCreateSchema.safeParse({ email: 'a@b.test', displayName: 'Al', password: 'short', role: 'AGENT' }).success).toBe(false);
  });
  it('settings: end hour must be after start; webhook must be https', () => {
    const base = { name: 'Acme', timezone: 'UTC', businessHoursStart: '09:00', businessHoursEnd: '17:00', businessDays: ['MONDAY'], defaultLanguage: 'en', emailProvider: '', slackWebhookUrl: '', aiProvider: '' };
    expect(settingsSchema.safeParse(base).success).toBe(true);
    expect(settingsSchema.safeParse({ ...base, businessHoursEnd: '08:00' }).success).toBe(false);
    expect(settingsSchema.safeParse({ ...base, slackWebhookUrl: 'http://insecure.example' }).success).toBe(false);
  });
  it('sla: resolution >= first response and percent list', () => {
    expect(slaSchema.safeParse({ firstResponseMinutes: 60, resolutionMinutes: 30, escalationAfterPercent: '50' }).success).toBe(false);
    expect(slaSchema.safeParse({ firstResponseMinutes: 30, resolutionMinutes: 60, escalationAfterPercent: '50, 80' }).success).toBe(true);
    expect(slaSchema.safeParse({ firstResponseMinutes: 30, resolutionMinutes: 60, escalationAfterPercent: '150' }).success).toBe(false);
  });
  it('workflow: rule needs conditions and actions; transition cannot be self-loop', () => {
    const ok = { name: 'wf', rules: [{ name: 'r', when: [{ field: 'priority', op: 'EQ', value: 'URGENT' }], then: [{ action: 'ESCALATE', param: '1' }] }], transitions: [{ from: 'NEW', to: 'OPEN' }] };
    expect(workflowSchema.safeParse(ok).success).toBe(true);
    expect(workflowSchema.safeParse({ ...ok, rules: [{ ...ok.rules[0], when: [] }] }).success).toBe(false);
    expect(workflowSchema.safeParse({ ...ok, transitions: [{ from: 'OPEN', to: 'OPEN' }] }).success).toBe(false);
  });
});
