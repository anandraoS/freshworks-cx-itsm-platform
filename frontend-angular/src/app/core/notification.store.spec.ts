import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { API_BASE, user } from '../testing/fixtures';
import { APP_CONFIG } from './config';
import type { AppNotification } from './models';
import { NOTIFICATION_POLL_MS, NotificationStore } from './notification.store';
import { TokenStore } from './token-store';

const n = (id: string, read = false): AppNotification => ({ id, type: 'TICKET', title: 't' + id, body: 'b', read, createdAt: '2026-01-01T00:00:00Z', ticketId: null });

describe('NotificationStore', () => {
  let http: HttpTestingController;
  let store: NotificationStore;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, showDemoHints: false } }],
    });
    TestBed.inject(TokenStore).set({ accessToken: 't', expiresAt: Date.now() + 60_000, user: user() });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(NotificationStore);
    TestBed.tick(); // run the constructor effect (starts polling for the signed-in user)
    http.expectOne(`${API_BASE}/api/v1/notifications/unread-count`).flush({ count: 2 });
    store.items.set([n('a'), n('b'), n('c', true)]);
  });
  afterEach(() => http.verify());

  it('loads the unread count for a signed-in user and exposes it as a signal', () => {
    expect(store.unread()).toBe(2);
    expect(NOTIFICATION_POLL_MS).toBe(30_000);
  });

  it('markRead is optimistic: item flips and badge drops immediately; re-syncs from the server on success', () => {
    store.markRead('a');
    expect(store.items().find((x) => x.id === 'a')?.read).toBeTrue();
    expect(store.unread()).toBe(1);
    http.expectOne({ method: 'POST', url: `${API_BASE}/api/v1/notifications/a/read` }).flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne(`${API_BASE}/api/v1/notifications/unread-count`).flush({ count: 1 });
    expect(store.unread()).toBe(1);
  });

  it('markRead rolls back item and badge when the request fails', () => {
    store.markRead('b');
    expect(store.unread()).toBe(1);
    http.expectOne({ method: 'POST', url: `${API_BASE}/api/v1/notifications/b/read` }).flush({ code: 'INTERNAL_ERROR', message: 'x' }, { status: 500, statusText: 'err' });
    expect(store.items().find((x) => x.id === 'b')?.read).toBeFalse();
    expect(store.unread()).toBe(2);
  });

  it('marking an already-read item does not change the badge', () => {
    store.markRead('c');
    expect(store.unread()).toBe(2);
    http.expectOne({ method: 'POST', url: `${API_BASE}/api/v1/notifications/c/read` }).flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne(`${API_BASE}/api/v1/notifications/unread-count`).flush({ count: 2 });
  });
});
