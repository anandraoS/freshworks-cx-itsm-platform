import { InjectionToken } from '@angular/core';
import { environment } from '../../environments/environment';

export interface AppConfig { apiBaseUrl: string; showDemoHints: boolean }

export const APP_CONFIG = new InjectionToken<AppConfig>('APP_CONFIG', {
  providedIn: 'root',
  factory: () => ({ apiBaseUrl: environment.apiBaseUrl, showDemoHints: environment.showDemoHints }),
});

export const API_PREFIX = '/api/v1';
