import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { ErrorReporter } from '../../core/global-error-handler';
import { NotificationBellComponent } from './notification-bell';

interface NavItem { to: string; label: string; exact: boolean }

@Component({
  selector: 'app-shell',
  imports: [RouterLink, RouterLinkActive, RouterOutlet, NotificationBellComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="app">
      <a href="#main" class="skip-link" (click)="skip($event)">Skip to main content</a>
      <header class="app-header">
        <div class="brand">Helpdesk</div>
        <span class="tenant-chip" data-testid="tenant-name" [attr.title]="'Tenant: ' + auth.user()?.tenant?.slug">{{ auth.user()?.tenant?.name }}</span>
        <nav aria-label="Main" class="main-nav">
          @for (i of items(); track i.to) {
            <a [routerLink]="i.to" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: i.exact }">{{ i.label }}</a>
          }
        </nav>
        <div class="header-right">
          <app-notification-bell />
          <span class="user-chip" data-testid="user-chip">{{ auth.user()?.displayName }} <small>({{ auth.user()?.roles?.join(', ') }})</small></span>
          <button type="button" class="btn btn-secondary btn-sm" (click)="signOut()">Sign out</button>
        </div>
      </header>
      <main id="main" tabindex="-1" class="app-main">
        @if (reporter.lastError(); as msg) {
          <div class="notice notice-error" role="alert">
            <span>{{ msg }}</span>
            <button type="button" class="btn btn-secondary btn-sm" (click)="reporter.dismiss()">Dismiss</button>
          </div>
        }
        <router-outlet />
      </main>
    </div>
  `,
})
export class ShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly reporter = inject(ErrorReporter);
  private readonly router = inject(Router);

  protected readonly items = computed<NavItem[]>(() => {
    if (this.auth.isCustomer()) {
      return [
        { to: '/portal', label: 'Home', exact: false },
        { to: '/tickets', label: 'My tickets', exact: true },
        { to: '/tickets/new', label: 'New ticket', exact: false },
        { to: '/kb', label: 'Help articles', exact: false },
      ];
    }
    const items: NavItem[] = [];
    if (this.auth.isManager()) items.push({ to: '/dashboard', label: 'Dashboard', exact: false });
    items.push({ to: '/tickets', label: 'Tickets', exact: true }, { to: '/kb', label: 'Knowledge base', exact: false });
    if (this.auth.isAdmin()) items.push({ to: '/admin', label: 'Administration', exact: false });
    else if (this.auth.isManager()) items.push({ to: '/admin/workflows', label: 'Workflows', exact: false });
    return items;
  });

  protected skip(e: Event): void {
    e.preventDefault();
    document.getElementById('main')?.focus();
  }

  protected async signOut(): Promise<void> {
    await this.auth.logout();
    await this.router.navigate(['/login']);
  }
}
