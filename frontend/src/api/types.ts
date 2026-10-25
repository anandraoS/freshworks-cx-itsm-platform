export type Role = 'CUSTOMER' | 'AGENT' | 'MANAGER' | 'ADMIN';
export type TicketStatus = 'NEW' | 'OPEN' | 'PENDING' | 'ON_HOLD' | 'RESOLVED' | 'CLOSED' | 'REOPENED';
export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';
export type Severity = 'MINOR' | 'MAJOR' | 'CRITICAL';
export type TicketType = 'SUPPORT' | 'IT_SERVICE';
export type SlaStatus = 'ON_TRACK' | 'AT_RISK' | 'BREACHED' | 'MET' | 'PAUSED';

export const STATUSES: TicketStatus[] = ['NEW', 'OPEN', 'PENDING', 'ON_HOLD', 'RESOLVED', 'CLOSED', 'REOPENED'];
export const PRIORITIES: Priority[] = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];
export const SEVERITIES: Severity[] = ['MINOR', 'MAJOR', 'CRITICAL'];
export const TICKET_TYPES: TicketType[] = ['SUPPORT', 'IT_SERVICE'];
export const SLA_STATUSES: SlaStatus[] = ['ON_TRACK', 'AT_RISK', 'BREACHED', 'MET', 'PAUSED'];
export const ROLES: Role[] = ['CUSTOMER', 'AGENT', 'MANAGER', 'ADMIN'];

/** Default transition map from the API contract; the workflow may override it server-side (server rejects with 422). */
export const DEFAULT_TRANSITIONS: Record<TicketStatus, TicketStatus[]> = {
  NEW: ['OPEN'],
  OPEN: ['PENDING', 'ON_HOLD', 'RESOLVED'],
  PENDING: ['OPEN', 'RESOLVED'],
  ON_HOLD: ['OPEN'],
  RESOLVED: ['CLOSED', 'REOPENED'],
  CLOSED: ['REOPENED'],
  REOPENED: ['OPEN', 'RESOLVED'],
};

export interface Tenant { id: string; slug: string; name: string }
export interface AuthUser { id: string; email: string; displayName: string; roles: Role[]; tenant: Tenant }
export interface LoginResponse { accessToken: string; expiresInSeconds: number; user: AuthUser }

export interface Page<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }

export interface UserRef { id: string; displayName: string }
export interface Attachment { id?: string; fileName: string; contentType: string; sizeBytes: number }
export interface Ticket {
  id: string; number: number | string; type: TicketType; title: string; description: string;
  status: TicketStatus; priority: Priority; severity: Severity;
  categoryId: string | null; categoryName: string | null; tags: string[];
  requester: UserRef; assignee: UserRef | null;
  createdAt: string; updatedAt: string;
  firstResponseDueAt: string | null; resolutionDueAt: string | null;
  firstRespondedAt: string | null; resolvedAt: string | null;
  slaStatus: SlaStatus; escalationLevel: number; reopenCount: number; version: number;
  attachments: Attachment[];
}
export interface TicketInput {
  type: TicketType; title: string; description: string; priority: Priority; severity: Severity;
  categoryId: string | null; tags: string[]; attachments: Attachment[];
}
export interface TicketComment {
  id: string; authorId: string; authorName: string; body: string;
  visibility: 'PUBLIC' | 'INTERNAL'; createdAt: string; aiGenerated: boolean;
}
export interface ActivityEntry { id: string; at: string; actorId: string; actorName: string; type: string; details: string }
export interface Category { id: string; name: string; type: TicketType; defaultPriority: Priority }

export interface AiSuggestion {
  suggestionId: string; provider: string; generated: boolean; summary: string;
  suggestedCategory: { id: string; name: string } | null; suggestedPriority: Priority | null;
  confidence: number; explanation: string;
  recommendedArticles: { id: string; title: string; score: number }[];
  suggestedReply: string | null;
  status: 'PENDING_REVIEW' | 'APPROVED' | 'EDITED_APPROVED' | 'REJECTED';
  degraded: boolean; fallbackMessage: string | null;
}

export interface Article {
  id: string; title: string; slug: string; body: string; summary: string;
  categoryId: string | null; categoryName: string | null;
  visibility: 'PUBLIC' | 'INTERNAL'; status: 'DRAFT' | 'PUBLISHED'; version: number;
  author: string | { id: string; displayName: string } | null; createdAt: string; updatedAt: string; relatedTicketIds: string[];
}
export interface ArticleInput { title: string; body: string; summary: string; categoryId: string | null; visibility: 'PUBLIC' | 'INTERNAL' }

export interface DashboardData {
  openTickets: number; ticketsByPriority: Record<Priority, number>;
  avgFirstResponseMinutes: number; avgResolutionMinutes: number; slaCompliancePercent: number;
  backlog: number; reopenedTickets: number; aiAcceptanceRatePercent: number;
  agentWorkload: { agentId: string; name: string; openCount: number }[];
  resolutionTrend: { date: string; resolved: number; created: number }[];
  tenantActivity: { ticketsCreatedLast7d: number; commentsLast7d: number; activeUsers: number };
}

export interface AppNotification { id: string; type: string; title: string; body: string; read: boolean; createdAt: string; ticketId: string | null }

export interface ManagedUser { id: string; email: string; displayName: string; roles: Role[]; active: boolean }
export interface TenantSettings {
  name: string; timezone: string; businessHoursStart: string; businessHoursEnd: string; businessDays: string[];
  defaultLanguage: string;
  integrations: { emailProvider: string; slackWebhookUrl: string; aiProvider: string };
}
export interface AuditEvent { id: string; at: string; actorId: string; actorEmail: string; action: string; entityType: string; entityId: string; details: string }

export interface WorkflowCondition { field: string; op: 'EQ' | 'NE' | 'GT' | 'LT' | 'IN' | 'CONTAINS'; value: string }
export interface WorkflowAction { action: 'ASSIGN_TO_ROLE_LEAST_LOADED' | 'SET_PRIORITY' | 'ESCALATE' | 'NOTIFY' | 'ADD_TAG'; params: Record<string, string> }
export interface WorkflowRule { name: string; when: WorkflowCondition[]; then: WorkflowAction[] }
export interface WorkflowTransition { from: TicketStatus; to: TicketStatus }
export interface WorkflowInput { name: string; rules: WorkflowRule[]; transitions: WorkflowTransition[] }
export interface Workflow extends WorkflowInput { id: string; version: number; active: boolean; createdAt: string }
export interface SlaPolicy { id: string; priority: Priority; firstResponseMinutes: number; resolutionMinutes: number; escalationAfterPercent: number[] }

export const WORKFLOW_OPS: WorkflowCondition['op'][] = ['EQ', 'NE', 'GT', 'LT', 'IN', 'CONTAINS'];
export const WORKFLOW_ACTIONS: WorkflowAction['action'][] = ['ASSIGN_TO_ROLE_LEAST_LOADED', 'SET_PRIORITY', 'ESCALATE', 'NOTIFY', 'ADD_TAG'];

export interface ApiFieldError { field: string; message: string }
export interface ApiErrorBody {
  timestamp?: string; status: number; error?: string; code: string; message: string; path?: string;
  correlationId?: string; fieldErrors?: ApiFieldError[];
}
