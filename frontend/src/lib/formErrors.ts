import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import { ApiError } from '../api/client';

/** Maps server fieldErrors onto RHF fields; returns true if at least one was mapped. */
export function applyFieldErrors<T extends FieldValues>(err: unknown, setError: UseFormSetError<T>, known: string[]): boolean {
  if (!(err instanceof ApiError) || err.fieldErrors.length === 0) return false;
  let any = false;
  for (const fe of err.fieldErrors) {
    if (known.includes(fe.field)) {
      setError(fe.field as Path<T>, { type: 'server', message: fe.message });
      any = true;
    }
  }
  return any;
}
