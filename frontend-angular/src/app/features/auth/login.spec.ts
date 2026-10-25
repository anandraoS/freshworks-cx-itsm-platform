import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { APP_CONFIG } from '../../core/config';
import { authInterceptor, errorInterceptor } from '../../core/interceptors';
import { API_BASE, user } from '../../testing/fixtures';
import { DEMO_PASSWORD, LoginPage, safeReturnUrl } from './login.page';

const URL = `${API_BASE}/api/v1`;

describe('LoginPage', () => {
  let fixture: ComponentFixture<LoginPage>;
  let http: HttpTestingController;
  let el: HTMLElement;

  const setup = (showDemoHints: boolean) => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
        { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, showDemoHints } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LoginPage);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  };
  const type = (sel: string, v: string) => { const i = el.querySelector(sel) as HTMLInputElement; i.value = v; i.dispatchEvent(new Event('input')); };
  const submit = () => (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));

  it('validates before calling the API', () => {
    setup(false);
    submit();
    fixture.detectChanges();
    expect(el.textContent).toContain('Organization is required');
    expect(el.textContent).toContain('Email is required');
    expect(el.textContent).toContain('Password is required');
    http.expectNone(`${URL}/auth/login`);
  });

  it('seed hints panel fills the form with the dummy credentials (dev only)', () => {
    setup(true);
    const hint = el.querySelector('.login-hints button') as HTMLButtonElement;
    hint.click();
    fixture.detectChanges();
    expect((el.querySelector('input[formcontrolname=tenantSlug]') as HTMLInputElement).value).toBe('acme-retail');
    expect((el.querySelector('input[formcontrolname=password]') as HTMLInputElement).value).toBe(DEMO_PASSWORD);
  });

  it('hints panel is absent when disabled (production default)', () => {
    setup(false);
    expect(el.querySelector('.login-hints')).toBeNull();
  });

  it('successful login lowercases the slug and navigates to the role home', async () => {
    setup(false);
    const nav = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);
    type('input[formcontrolname=tenantSlug]', 'ACME-Retail');
    type('input[formcontrolname=email]', 'manager@acme.test');
    type('input[formcontrolname=password]', 'pw');
    submit();
    const req = http.expectOne(`${URL}/auth/login`);
    expect(req.request.body.tenantSlug).toBe('acme-retail');
    req.flush({ accessToken: 'jwt', expiresInSeconds: 3600, user: user(['MANAGER']) });
    await fixture.whenStable();
    expect(nav).toHaveBeenCalledWith('/dashboard', { replaceUrl: true });
    expect(TestBed.inject(AuthService).user()?.roles).toEqual(['MANAGER']);
  });

  it('401 shows a generic message that does not reveal whether tenant or email exist', async () => {
    setup(false);
    type('input[formcontrolname=tenantSlug]', 'acme-retail');
    type('input[formcontrolname=email]', 'who@acme.test');
    type('input[formcontrolname=password]', 'bad');
    submit();
    http.expectOne(`${URL}/auth/login`).flush({ code: 'UNAUTHENTICATED', message: 'No such user who@acme.test' }, { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();
    fixture.detectChanges();
    const err = el.querySelector('.notice-error') as HTMLElement;
    expect(err.textContent).toBe('Invalid organization, email or password.');
    expect(err.textContent).not.toContain('who@');
  });

  it('safeReturnUrl only accepts same-origin absolute paths (no open redirect)', () => {
    expect(safeReturnUrl('/tickets?status=OPEN')).toBe('/tickets?status=OPEN');
    expect(safeReturnUrl('//evil.example')).toBeNull();
    expect(safeReturnUrl('https://evil.example')).toBeNull();
    expect(safeReturnUrl('/login')).toBeNull();
    expect(safeReturnUrl(undefined)).toBeNull();
  });
});
