import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-admin-layout',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-head"><h1>Administration</h1></div>
    <nav aria-label="Administration sections" class="subnav">
      @if (auth.isAdmin()) {
        <a routerLink="/admin/settings" routerLinkActive="active">Organization</a>
        <a routerLink="/admin/users" routerLinkActive="active">Users &amp; roles</a>
      }
      <a routerLink="/admin/workflows" routerLinkActive="active">Workflows</a>
      @if (auth.isAdmin()) {
        <a routerLink="/admin/sla" routerLinkActive="active">SLA policies</a>
        <a routerLink="/admin/categories" routerLinkActive="active">Categories</a>
        <a routerLink="/admin/audit" routerLinkActive="active">Audit activity</a>
      }
    </nav>
    <router-outlet />
  `,
})
export class AdminLayout {
  protected readonly auth = inject(AuthService);
}
