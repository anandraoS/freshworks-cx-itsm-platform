import { effect, inject, Injectable, signal, untracked } from '@angular/core';
import { AuthService } from './auth.service';
import { CategoryApi, KbApi, UserApi } from './endpoints';
import type { Category, ManagedUser } from './models';

/**
 * Small tenant-scoped lookup caches (categories, assignable users, KB categories) held in signals.
 * The caches are dropped whenever the signed-in user changes, so one tenant's data can never be shown to another.
 */
@Injectable({ providedIn: 'root' })
export class LookupsService {
  private readonly auth = inject(AuthService);
  private readonly categoryApi = inject(CategoryApi);
  private readonly userApi = inject(UserApi);
  private readonly kbApi = inject(KbApi);

  readonly categories = signal<Category[]>([]);
  readonly users = signal<ManagedUser[]>([]);
  readonly articleCategories = signal<{ id: string; name: string }[]>([]);
  private loaded = { categories: false, users: false, articleCategories: false };

  constructor() {
    effect(() => {
      this.auth.user(); // reset on every identity change (login, logout, 401)
      untracked(() => {
        this.categories.set([]); this.users.set([]); this.articleCategories.set([]);
        this.loaded = { categories: false, users: false, articleCategories: false };
      });
    });
  }

  ensureCategories(): void {
    if (this.loaded.categories || !this.auth.user()) return;
    this.loaded.categories = true;
    this.categoryApi.list().subscribe({ next: (c) => this.categories.set(c), error: () => { this.loaded.categories = false; } });
  }
  /** AGENT+ only: customers never call /users. */
  ensureUsers(): void {
    if (this.loaded.users || !this.auth.isStaff()) return;
    this.loaded.users = true;
    this.userApi.list().subscribe({ next: (u) => this.users.set(u), error: () => { this.loaded.users = false; } });
  }
  ensureArticleCategories(): void {
    if (this.loaded.articleCategories || !this.auth.user()) return;
    this.loaded.articleCategories = true;
    this.kbApi.categories().subscribe({ next: (c) => this.articleCategories.set(c), error: () => { this.loaded.articleCategories = false; } });
  }
  refreshCategories(): void { this.loaded.categories = false; this.ensureCategories(); }
  refreshUsers(): void { this.loaded.users = false; this.ensureUsers(); }

  assignable(): ManagedUser[] {
    return this.users().filter((u) => u.active !== false && u.roles.some((r) => r !== 'CUSTOMER'));
  }
}
