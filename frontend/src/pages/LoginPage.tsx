import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ApiError, describeError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { homeFor } from '../auth/roles';
import { Button, Input } from '../components/ui';
import { loginSchema, type LoginForm } from '../lib/schemas';

/** Local-demo seed logins (dummy data from docs/API-CONTRACT.md). Shown only in dev builds or when VITE_SHOW_DEMO_HINTS=true. */
export const SEED_LOGINS = [
  { tenant: 'acme-retail', email: 'admin@acme.test', role: 'ADMIN' },
  { tenant: 'acme-retail', email: 'manager@acme.test', role: 'MANAGER' },
  { tenant: 'acme-retail', email: 'agent1@acme.test', role: 'AGENT' },
  { tenant: 'acme-retail', email: 'customer1@acme.test', role: 'CUSTOMER' },
  { tenant: 'globex-it', email: 'admin@globex.test', role: 'ADMIN' },
  { tenant: 'globex-it', email: 'agent1@globex.test', role: 'AGENT' },
  { tenant: 'initech-health', email: 'admin@initech.test', role: 'ADMIN' },
];
const DEMO_PASSWORD = 'Passw0rd!demo';
const showHints = import.meta.env.DEV || import.meta.env.VITE_SHOW_DEMO_HINTS === 'true';

export function LoginPage() {
  const { user, login, sessionExpired } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [formError, setFormError] = useState<string | null>(null);
  const { register, handleSubmit, setValue, formState: { errors, isSubmitting } } = useForm<LoginForm>({
    resolver: zodResolver(loginSchema),
    defaultValues: { tenantSlug: '', email: '', password: '' },
  });

  if (user) return <Navigate to={homeFor(user)} replace />;

  async function onSubmit(v: LoginForm) {
    setFormError(null);
    try {
      const u = await login(v.tenantSlug.toLowerCase(), v.email, v.password);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from && from !== '/login' ? from : homeFor(u), { replace: true });
    } catch (e) {
      // Generic message on 401/403 so we do not reveal whether tenant or email exists.
      if (e instanceof ApiError && (e.kind === 'unauthenticated' || e.kind === 'forbidden')) setFormError('Invalid organization, email or password.');
      else setFormError(describeError(e));
    }
  }

  return (
    <div className="login-wrap">
      <div className="login-card">
        <h1>Sign in</h1>
        {sessionExpired && <p className="notice notice-warn" role="status">Your session expired. Please sign in again.</p>}
        <form onSubmit={handleSubmit(onSubmit)} noValidate aria-label="Sign in">
          <Input label="Organization (tenant slug)" autoComplete="organization" required {...register('tenantSlug')} error={errors.tenantSlug?.message} />
          <Input label="Email" type="email" autoComplete="username" required {...register('email')} error={errors.email?.message} />
          <Input label="Password" type="password" autoComplete="current-password" required {...register('password')} error={errors.password?.message} />
          {formError && <p className="notice notice-error" role="alert">{formError}</p>}
          <Button type="submit" loading={isSubmitting} disabled={isSubmitting}>Sign in</Button>
        </form>
      </div>
      {showHints && (
        <aside className="login-hints" aria-label="Local demo logins">
          <h2>Local demo logins</h2>
          <p className="muted">Dummy seed data for local development only. Password for all: <code>{DEMO_PASSWORD}</code></p>
          <ul>
            {SEED_LOGINS.map((s) => (
              <li key={s.email}>
                <button type="button" className="link-btn" onClick={() => {
                  setValue('tenantSlug', s.tenant); setValue('email', s.email); setValue('password', DEMO_PASSWORD);
                }}>
                  {s.email}
                </button>
                <span className="muted"> — {s.tenant} · {s.role}</span>
              </li>
            ))}
          </ul>
        </aside>
      )}
    </div>
  );
}
