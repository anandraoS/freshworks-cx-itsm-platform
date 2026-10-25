import { useQuery } from '@tanstack/react-query';
import { categoryApi, kbApi, userApi } from '../api/endpoints';
import { useAuth, useTenantId } from '../auth/AuthContext';
import { atLeast } from '../auth/roles';
import { qk } from '../lib/queryKeys';

export function useCategories() {
  const t = useTenantId();
  return useQuery({ queryKey: qk.categories(t), queryFn: categoryApi.list, staleTime: 5 * 60_000 });
}

/** Users for assignment dropdowns (AGENT+ only; customers never call /users). */
export function useAssignableUsers() {
  const t = useTenantId();
  const { user } = useAuth();
  return useQuery({ queryKey: qk.users(t), queryFn: userApi.list, enabled: atLeast(user, 'AGENT'), staleTime: 60_000 });
}

export function useArticleCategories() {
  const t = useTenantId();
  return useQuery({ queryKey: qk.articleCategories(t), queryFn: kbApi.categories, staleTime: 5 * 60_000 });
}
