import { HttpContextToken } from '@angular/common/http';

/** Set on requests (the login call) that must not trigger the global 401 -> logout handling. */
export const SKIP_AUTH_HANDLING = new HttpContextToken<boolean>(() => false);
