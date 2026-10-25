import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { APP_CONFIG } from '../../core/config';
import { AuthService } from '../../core/auth.service';
import { describeError, isKind } from '../../core/errors';
import { buildLoginForm } from '../../core/form-schemas';
import { homeFor } from '../../core/roles';
import { validateAll } from '../../core/validators';
import { ButtonComponent } from '../../shared/ui/button';
import { FieldComponent, FieldControlDirective } from '../../shared/ui/field';

/** Local-demo seed logins (dummy data from docs/API-CONTRACT.md). Shown only in dev builds or when showDemoHints is set. */
export const SEED_LOGINS = [
  { tenant: 'acme-retail', email: 'admin@acme.test', role: 'ADMIN' },
  { tenant: 'acme-retail', email: 'manager@acme.test', role: 'MANAGER' },
  { tenant: 'acme-retail', email: 'agent1@acme.test', role: 'AGENT' },
  { tenant: 'acme-retail', email: 'customer1@acme.test', role: 'CUSTOMER' },
  { tenant: 'globex-it', email: 'admin@globex.test', role: 'ADMIN' },
  { tenant: 'globex-it', email: 'agent1@globex.test', role: 'AGENT' },
  { tenant: 'initech-health', email: 'admin@initech.test', role: 'ADMIN' },
];
export const DEMO_PASSWORD = 'Passw0rd!demo';

/** Only same-origin absolute paths are accepted as a post-login target (no open redirect). */
export function safeReturnUrl(from: string | null | undefined): string | null {
  if (!from || !from.startsWith('/') || from.startsWith('//') || from.startsWith('/login')) return null;
  return from;
}

@Component({
  selector: 'app-login-page',
  imports: [ReactiveFormsModule, ButtonComponent, FieldComponent, FieldControlDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="login-wrap">
      <div class="login-card">
        <h1>Sign in</h1>
        @if (auth.sessionExpired()) { <p class="notice notice-warn" role="status">Your session expired. Please sign in again.</p> }
        <form [formGroup]="form" (ngSubmit)="submit()" novalidate aria-label="Sign in">
          <app-field label="Organization (tenant slug)" [control]="form.controls.tenantSlug" required>
            <input appFieldControl formControlName="tenantSlug" autocomplete="organization" />
          </app-field>
          <app-field label="Email" [control]="form.controls.email" required>
            <input appFieldControl formControlName="email" type="email" autocomplete="username" />
          </app-field>
          <app-field label="Password" [control]="form.controls.password" required>
            <input appFieldControl formControlName="password" type="password" autocomplete="current-password" />
          </app-field>
          @if (formError(); as e) { <p class="notice notice-error" role="alert">{{ e }}</p> }
          <button appButton type="submit" [loading]="busy()">Sign in</button>
        </form>
      </div>
      @if (cfg.showDemoHints) {
        <aside class="login-hints" aria-label="Local demo logins">
          <h2>Local demo logins</h2>
          <p class="muted">Dummy seed data for local development only. Password for all: <code>{{ demoPassword }}</code></p>
          <ul>
            @for (s of seeds; track s.email) {
              <li>
                <button type="button" class="link-btn" (click)="fill(s)">{{ s.email }}</button>
                <span class="muted"> — {{ s.tenant }} · {{ s.role }}</span>
              </li>
            }
          </ul>
        </aside>
      }
    </div>
  `,
})
export class LoginPage {
  protected readonly auth = inject(AuthService);
  protected readonly cfg = inject(APP_CONFIG);
  private readonly router = inject(Router);
  /** bound from the `?from=` query param by withComponentInputBinding */
  readonly from = input<string | undefined>(undefined);

  protected readonly form = buildLoginForm();
  protected readonly formError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly seeds = SEED_LOGINS;
  protected readonly demoPassword = DEMO_PASSWORD;

  protected fill(s: { tenant: string; email: string }): void {
    this.form.setValue({ tenantSlug: s.tenant, email: s.email, password: DEMO_PASSWORD });
  }

  protected async submit(): Promise<void> {
    if (this.busy() || !validateAll(this.form)) return;
    this.formError.set(null);
    this.busy.set(true);
    const v = this.form.getRawValue();
    try {
      const user = await this.auth.login(v.tenantSlug.trim().toLowerCase(), v.email.trim(), v.password);
      await this.router.navigateByUrl(safeReturnUrl(this.from()) ?? homeFor(user), { replaceUrl: true });
    } catch (e) {
      // Generic message on 401/403 so we do not reveal whether tenant or email exists.
      this.formError.set(isKind(e, 'unauthenticated') || isKind(e, 'forbidden') ? 'Invalid organization, email or password.' : describeError(e));
    } finally {
      this.busy.set(false);
    }
  }
}
