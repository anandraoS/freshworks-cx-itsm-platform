import type { AuthUser, Role } from './models';

const RANK: Record<Role, number> = { CUSTOMER: 0, AGENT: 1, MANAGER: 2, ADMIN: 3 };

export function rankOf(user: AuthUser | null): number {
  if (!user) return -1;
  return Math.max(-1, ...user.roles.map((r) => RANK[r] ?? -1));
}

export function isCustomerOnly(user: AuthUser | null): boolean {
  return !!user && user.roles.length > 0 && user.roles.every((r) => r === 'CUSTOMER');
}

/** AGENT+ means AGENT, MANAGER or ADMIN. */
export function atLeast(user: AuthUser | null, role: Role): boolean {
  return rankOf(user) >= RANK[role];
}

export function homeFor(user: AuthUser | null): string {
  if (!user) return '/login';
  if (isCustomerOnly(user)) return '/portal';
  if (atLeast(user, 'MANAGER')) return '/dashboard';
  return '/tickets';
}
