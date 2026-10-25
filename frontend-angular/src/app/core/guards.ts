import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import type { Role } from './models';
import { atLeast, homeFor, isCustomerOnly } from './roles';

/** Requires a signed-in user; otherwise redirects to /login remembering where the user wanted to go. */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.user()) return true;
  return router.createUrlTree(['/login'], { queryParams: { from: state.url } });
};

export interface RoleRule { min?: Role; staffOnly?: boolean }

/** Role guard factory: at least `min`, and optionally not customer-only; otherwise /forbidden. */
export function roleGuard(rule: RoleRule): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    const router = inject(Router);
    const user = auth.user();
    if (!user) return router.createUrlTree(['/login']);
    const ok = (!rule.min || atLeast(user, rule.min)) && (!rule.staffOnly || !isCustomerOnly(user));
    return ok ? true : router.createUrlTree(['/forbidden']);
  };
}

/** Redirect "/" to the role-appropriate home. */
export const homeRedirectGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return inject(Router).createUrlTree([homeFor(auth.user())]);
};

/** Login page: already signed-in users go straight home. */
export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const u = auth.user();
  return u ? inject(Router).createUrlTree([homeFor(u)]) : true;
};
