/**
 * Query-key convention: ['t', tenantId, resource, ...params].
 * The tenant id is always the first discriminator, so a (re-)login into another tenant can never read cached
 * data of the previous one, and `qk.all(tenantId)` can wipe everything of one tenant. The cache is also cleared on logout.
 */
export const qk = {
  all: (t: string) => ['t', t] as const,
  tickets: (t: string) => ['t', t, 'tickets'] as const,
  ticketList: (t: string, f: unknown) => ['t', t, 'tickets', 'list', f] as const,
  ticket: (t: string, id: string) => ['t', t, 'tickets', 'detail', id] as const,
  comments: (t: string, id: string) => ['t', t, 'tickets', 'detail', id, 'comments'] as const,
  activity: (t: string, id: string) => ['t', t, 'tickets', 'detail', id, 'activity'] as const,
  aiSuggestions: (t: string, id: string) => ['t', t, 'tickets', 'detail', id, 'ai'] as const,
  categories: (t: string) => ['t', t, 'categories'] as const,
  users: (t: string) => ['t', t, 'users'] as const,
  articles: (t: string) => ['t', t, 'articles'] as const,
  articleList: (t: string, f: unknown) => ['t', t, 'articles', 'list', f] as const,
  article: (t: string, id: string) => ['t', t, 'articles', 'detail', id] as const,
  articleVersions: (t: string, id: string) => ['t', t, 'articles', 'detail', id, 'versions'] as const,
  articleCategories: (t: string) => ['t', t, 'article-categories'] as const,
  dashboard: (t: string, days: number) => ['t', t, 'analytics', 'dashboard', days] as const,
  notifications: (t: string) => ['t', t, 'notifications'] as const,
  notificationList: (t: string, unread: boolean) => ['t', t, 'notifications', 'list', unread] as const,
  unreadCount: (t: string) => ['t', t, 'notifications', 'unread-count'] as const,
  settings: (t: string) => ['t', t, 'settings'] as const,
  audit: (t: string, f: unknown) => ['t', t, 'audit', f] as const,
  workflows: (t: string) => ['t', t, 'workflows'] as const,
  sla: (t: string) => ['t', t, 'sla'] as const,
};
