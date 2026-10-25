import { z } from 'zod';
import { PRIORITIES, ROLES, SEVERITIES, TICKET_TYPES } from '../api/types';

export const loginSchema = z.object({
  tenantSlug: z.string().trim().min(1, 'Organization is required').regex(/^[a-z0-9-]+$/i, 'Use letters, numbers and dashes only'),
  email: z.string().trim().min(1, 'Email is required').email('Enter a valid email'),
  password: z.string().min(1, 'Password is required'),
});
export type LoginForm = z.infer<typeof loginSchema>;

export const ticketSchema = z.object({
  type: z.enum(TICKET_TYPES as [string, ...string[]]),
  title: z.string().trim().min(5, 'Title must be at least 5 characters').max(200, 'Title must be at most 200 characters'),
  description: z.string().trim().min(10, 'Please describe the issue (at least 10 characters)').max(10000, 'Description is too long'),
  priority: z.enum(PRIORITIES as [string, ...string[]]),
  severity: z.enum(SEVERITIES as [string, ...string[]]),
  categoryId: z.string(),
  tags: z.string().max(200, 'Tags are too long').refine((s) => s.split(',').map((t) => t.trim()).filter(Boolean).length <= 10, 'At most 10 tags'),
});
export type TicketForm = z.infer<typeof ticketSchema>;

export function parseTags(s: string): string[] {
  return Array.from(new Set(s.split(',').map((t) => t.trim()).filter(Boolean)));
}

export const commentSchema = z.object({
  body: z.string().trim().min(1, 'Comment cannot be empty').max(5000, 'Comment is too long'),
});

export const transitionSchema = z.object({
  reason: z.string().trim().min(3, 'Please give a reason (at least 3 characters)').max(500, 'Reason is too long'),
});

export const articleSchema = z.object({
  title: z.string().trim().min(3, 'Title must be at least 3 characters').max(200),
  summary: z.string().trim().max(500, 'Summary is too long'),
  body: z.string().trim().min(10, 'Body must be at least 10 characters'),
  categoryId: z.string(),
  visibility: z.enum(['PUBLIC', 'INTERNAL']),
});
export type ArticleForm = z.infer<typeof articleSchema>;

export const userCreateSchema = z.object({
  email: z.string().trim().email('Enter a valid email'),
  displayName: z.string().trim().min(2, 'Name is required').max(100),
  password: z.string().min(10, 'Password must be at least 10 characters'),
  role: z.enum(ROLES as [string, ...string[]]),
});
export type UserCreateForm = z.infer<typeof userCreateSchema>;

export const settingsSchema = z.object({
  name: z.string().trim().min(2, 'Organization name is required').max(120),
  timezone: z.string().trim().min(1, 'Timezone is required'),
  businessHoursStart: z.string().regex(/^\d{2}:\d{2}$/, 'Use HH:MM'),
  businessHoursEnd: z.string().regex(/^\d{2}:\d{2}$/, 'Use HH:MM'),
  businessDays: z.array(z.string()).min(1, 'Select at least one business day'),
  defaultLanguage: z.string().trim().min(2, 'Language is required'),
  emailProvider: z.string().trim().max(50),
  slackWebhookUrl: z.string().trim().max(300).refine((v) => v === '' || /^https:\/\//.test(v), 'Must start with https://'),
  aiProvider: z.string().trim().max(50),
}).refine((v) => v.businessHoursStart < v.businessHoursEnd, { path: ['businessHoursEnd'], message: 'End must be after start' });
export type SettingsForm = z.infer<typeof settingsSchema>;

export const categorySchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(80),
  type: z.enum(TICKET_TYPES as [string, ...string[]]),
  defaultPriority: z.enum(PRIORITIES as [string, ...string[]]),
});
export type CategoryForm = z.infer<typeof categorySchema>;

export const slaSchema = z.object({
  firstResponseMinutes: z.coerce.number().int().min(1, 'Must be at least 1'),
  resolutionMinutes: z.coerce.number().int().min(1, 'Must be at least 1'),
  escalationAfterPercent: z.string().refine(
    (s) => s.split(',').map((x) => x.trim()).filter(Boolean).every((x) => /^\d+$/.test(x) && +x > 0 && +x <= 100),
    'Comma-separated percentages between 1 and 100',
  ),
}).refine((v) => v.resolutionMinutes >= v.firstResponseMinutes, { path: ['resolutionMinutes'], message: 'Resolution must be >= first response' });
export type SlaForm = z.infer<typeof slaSchema>;

export const workflowSchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(100),
  rules: z.array(z.object({
    name: z.string().trim().min(1, 'Rule name is required'),
    when: z.array(z.object({ field: z.string().trim().min(1, 'Field required'), op: z.string(), value: z.string().trim().min(1, 'Value required') })).min(1, 'At least one condition'),
    then: z.array(z.object({ action: z.string(), param: z.string().trim() })).min(1, 'At least one action'),
  })),
  transitions: z.array(z.object({ from: z.string(), to: z.string() })).refine(
    (t) => t.every((x) => x.from !== x.to), 'A transition cannot go to the same status',
  ),
});
export type WorkflowForm = z.infer<typeof workflowSchema>;
