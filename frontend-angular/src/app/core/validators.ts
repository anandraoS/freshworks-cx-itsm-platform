import { AbstractControl, FormGroup, ValidationErrors, ValidatorFn } from '@angular/forms';
import { ApiError } from './errors';

/**
 * Validators mirror the Zod rules of the React app. Every error value is `{ message }`, so a field can
 * show the first message without a per-validator lookup table. Values are trimmed before checking (Zod `.trim()`).
 */
const txt = (c: AbstractControl): string => (typeof c.value === 'string' ? c.value.trim() : c.value == null ? '' : String(c.value));
const fail = (key: string, message: string): ValidationErrors => ({ [key]: { message } });

export const required = (message: string): ValidatorFn => (c) => (txt(c).length > 0 ? null : fail('required', message));
export const minLen = (n: number, message: string): ValidatorFn => (c) => (txt(c).length >= n ? null : fail('minlength', message));
export const maxLen = (n: number, message: string): ValidatorFn => (c) => (txt(c).length <= n ? null : fail('maxlength', message));
export const rawMinLen = (n: number, message: string): ValidatorFn => (c) => (String(c.value ?? '').length >= n ? null : fail('minlength', message));
export const matches = (re: RegExp, message: string): ValidatorFn => (c) => (txt(c) === '' || re.test(txt(c)) ? null : fail('pattern', message));
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
export const emailFormat = (message: string): ValidatorFn => (c) => (txt(c) === '' || EMAIL_RE.test(txt(c)) ? null : fail('email', message));
export const custom = (key: string, message: string, ok: (value: string) => boolean): ValidatorFn =>
  (c) => (ok(txt(c)) ? null : fail(key, message));
export const minNumber = (n: number, message: string): ValidatorFn => (c) => {
  const v = Number(c.value);
  return Number.isInteger(v) && v >= n ? null : fail('min', message);
};
export const minItems = (n: number, message: string): ValidatorFn => (c) => (Array.isArray(c.value) && c.value.length >= n ? null : fail('minitems', message));

/** First human-readable error message of a control (or null when valid). */
export function firstError(c: AbstractControl | null): string | null {
  const errs = c?.errors;
  if (!errs) return null;
  for (const v of Object.values(errs)) {
    if (v && typeof v === 'object' && 'message' in v) return String((v as { message: unknown }).message);
  }
  return 'Invalid value';
}

/** Show errors only after the user interacted (or tried to submit). */
export function shouldShow(c: AbstractControl | null): boolean {
  return !!c && c.invalid && (c.touched || c.dirty);
}

/** Map server `fieldErrors` onto controls; returns true if at least one was mapped. */
export function applyFieldErrors(err: unknown, form: FormGroup, known: string[]): boolean {
  if (!(err instanceof ApiError) || err.fieldErrors.length === 0) return false;
  let any = false;
  for (const fe of err.fieldErrors) {
    const ctrl = known.includes(fe.field) ? form.get(fe.field) : null;
    if (ctrl) {
      ctrl.setErrors({ server: { message: fe.message } });
      ctrl.markAsTouched();
      any = true;
    }
  }
  return any;
}

/** Marks everything touched so all errors show; returns form validity. */
export function validateAll(form: FormGroup): boolean {
  form.markAllAsTouched();
  return form.valid;
}
