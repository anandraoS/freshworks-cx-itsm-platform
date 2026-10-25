import { forwardRef, useId, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost';
  size?: 'sm' | 'md';
  loading?: boolean;
}

export function Button({ variant = 'primary', size = 'md', loading, disabled, children, className = '', type = 'button', ...rest }: ButtonProps) {
  return (
    <button
      type={type}
      className={`btn btn-${variant} btn-${size} ${className}`.trim()}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading && <span className="spinner" aria-hidden="true" />}
      {children}
    </button>
  );
}

interface FieldShellProps { label: string; error?: string; hint?: string; id: string; children: ReactNode; required?: boolean }

function FieldShell({ label, error, hint, id, children, required }: FieldShellProps) {
  return (
    <div className={`field${error ? ' field-error' : ''}`}>
      <label htmlFor={id}>
        {label}
        {required && <span aria-hidden="true" className="req"> *</span>}
      </label>
      {children}
      {hint && !error && <p className="field-hint" id={`${id}-hint`}>{hint}</p>}
      {error && <p className="field-msg" id={`${id}-err`} role="alert">{error}</p>}
    </div>
  );
}

function describedBy(id: string, error?: string, hint?: string) {
  return error ? `${id}-err` : hint ? `${id}-hint` : undefined;
}

interface InputProps extends InputHTMLAttributes<HTMLInputElement> { label: string; error?: string; hint?: string }
export const Input = forwardRef<HTMLInputElement, InputProps>(function Input({ label, error, hint, id, required, ...rest }, ref) {
  const gen = useId();
  const fid = id ?? gen;
  return (
    <FieldShell id={fid} label={label} error={error} hint={hint} required={required}>
      <input ref={ref} id={fid} aria-invalid={!!error || undefined} aria-describedby={describedBy(fid, error, hint)} aria-required={required || undefined} {...rest} />
    </FieldShell>
  );
});

interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> { label: string; error?: string; hint?: string }
export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select({ label, error, hint, id, required, children, ...rest }, ref) {
  const gen = useId();
  const fid = id ?? gen;
  return (
    <FieldShell id={fid} label={label} error={error} hint={hint} required={required}>
      <select ref={ref} id={fid} aria-invalid={!!error || undefined} aria-describedby={describedBy(fid, error, hint)} {...rest}>{children}</select>
    </FieldShell>
  );
});

interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> { label: string; error?: string; hint?: string }
export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea({ label, error, hint, id, required, ...rest }, ref) {
  const gen = useId();
  const fid = id ?? gen;
  return (
    <FieldShell id={fid} label={label} error={error} hint={hint} required={required}>
      <textarea ref={ref} id={fid} aria-invalid={!!error || undefined} aria-describedby={describedBy(fid, error, hint)} {...rest} />
    </FieldShell>
  );
});
