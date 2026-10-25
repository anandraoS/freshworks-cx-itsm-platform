import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthApi } from './endpoints';
import type { AuthUser } from './models';
import { atLeast, isCustomerOnly } from './roles';
import { TokenStore } from './token-store';

/** Session state as signals. The signed-in user is the single source of truth for role-dependent UI. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(AuthApi);
  private readonly store = inject(TokenStore);
  private readonly router = inject(Router);

  private readonly _user = signal<AuthUser | null>(this.store.get()?.user ?? null);
  private readonly _sessionExpired = signal(false);

  readonly user = this._user.asReadonly();
  readonly sessionExpired = this._sessionExpired.asReadonly();
  readonly tenantId = computed(() => this._user()?.tenant.id ?? 'anonymous');
  readonly isCustomer = computed(() => isCustomerOnly(this._user()));
  readonly isStaff = computed(() => atLeast(this._user(), 'AGENT'));
  readonly isManager = computed(() => atLeast(this._user(), 'MANAGER'));
  readonly isAdmin = computed(() => atLeast(this._user(), 'ADMIN'));

  async login(tenantSlug: string, email: string, password: string): Promise<AuthUser> {
    const res = await firstValueFrom(this.api.login({ tenantSlug, email, password }));
    this.store.set({ accessToken: res.accessToken, expiresAt: Date.now() + res.expiresInSeconds * 1000, user: res.user });
    this._sessionExpired.set(false);
    this._user.set(res.user);
    return res.user;
  }

  async logout(): Promise<void> {
    try { await firstValueFrom(this.api.logout()); } catch { /* best effort: the token is dropped locally regardless */ }
    this._sessionExpired.set(false);
    this.clear();
  }

  /** Called by the error interceptor on any 401 from an authenticated call. */
  handleUnauthorized(): void {
    if (this.store.get() || this._user()) this._sessionExpired.set(true);
    this.clear();
    void this.router.navigate(['/login']);
  }

  private clear(): void {
    this.store.clear();
    this._user.set(null);
  }
}
