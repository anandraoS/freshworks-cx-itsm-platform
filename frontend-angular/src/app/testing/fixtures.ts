import type { AuthUser, Role, Ticket } from '../core/models';

export const tenant = { id: 't-1', slug: 'acme-retail', name: 'Acme Retail' };

export function user(roles: Role[] = ['AGENT'], over: Partial<AuthUser> = {}): AuthUser {
  return { id: 'u-1', email: 'agent1@acme.test', displayName: 'Agent One', roles, tenant, ...over };
}

export function ticket(over: Partial<Ticket> = {}): Ticket {
  return {
    id: 'tk-1', number: 42, type: 'SUPPORT', title: 'Printer on fire', description: 'It is smoking quite a lot.',
    status: 'OPEN', priority: 'HIGH', severity: 'MAJOR', categoryId: null, categoryName: null, tags: [],
    requester: { id: 'u-9', displayName: 'Cust' }, assignee: null, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
    firstResponseDueAt: null, resolutionDueAt: null, firstRespondedAt: null, resolvedAt: null, slaStatus: 'ON_TRACK',
    escalationLevel: 0, reopenCount: 0, version: 3, attachments: [], ...over,
  };
}

export const API_BASE = 'http://api.test';
