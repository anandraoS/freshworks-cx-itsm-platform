import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, buildQuery, describeError, mapKind, request, setUnauthorizedHandler } from '../api/client';
import { tokenStore } from '../auth/tokenStore';
import { jsonResponse, makeUser } from './utils';

const fetchMock = vi.fn();
beforeEach(() => { vi.stubGlobal('fetch', fetchMock); fetchMock.mockReset(); });
afterEach(() => { vi.unstubAllGlobals(); setUnauthorizedHandler(null); });

describe('mapKind', () => {
  it.each([
    ['VALIDATION_FAILED', 400, 'validation'], ['UNAUTHENTICATED', 401, 'unauthenticated'], ['FORBIDDEN', 403, 'forbidden'],
    ['NOT_FOUND', 404, 'not_found'], ['TICKET_VERSION_CONFLICT', 409, 'version_conflict'], ['DUPLICATE_SUBMISSION', 409, 'duplicate'],
    ['INVALID_TRANSITION', 422, 'invalid_transition'], ['RATE_LIMITED', 429, 'rate_limited'], ['AI_UNAVAILABLE', 503, 'ai_unavailable'],
    ['INTERNAL_ERROR', 500, 'server'], ['', 502, 'server'], ['', 0, 'network'],
  ])('%s/%i -> %s', (code, status, kind) => expect(mapKind(status, code)).toBe(kind));
});

describe('request', () => {
  it('sends Authorization, X-Correlation-Id and JSON body; builds query without empty values', async () => {
    tokenStore.set({ accessToken: 'jwt-123', expiresAt: Date.now() + 60_000, user: makeUser(['AGENT']) });
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));
    await request('/tickets', { method: 'POST', body: { a: 1 }, params: { page: 0, q: '', status: 'OPEN', x: undefined } });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit & { headers: Record<string, string> }];
    expect(url).toBe('http://localhost:8080/api/v1/tickets?page=0&status=OPEN');
    expect(init.headers.Authorization).toBe('Bearer jwt-123');
    expect(init.headers['X-Correlation-Id']).toMatch(/.+/);
    expect(init.headers['Content-Type']).toBe('application/json');
    expect(init.body).toBe('{"a":1}');
  });

  it('generates a different correlation id per request', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse({})));
    await request('/a'); await request('/b');
    const ids = fetchMock.mock.calls.map((c) => (c[1] as { headers: Record<string, string> }).headers['X-Correlation-Id']);
    expect(ids[0]).not.toBe(ids[1]);
  });

  it('maps an error body to ApiError with fieldErrors and correlationId', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ status: 400, code: 'VALIDATION_FAILED', message: 'bad', correlationId: 'c-1', fieldErrors: [{ field: 'title', message: 'must not be blank' }] }, 400));
    const err = await request('/tickets').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    const e = err as ApiError;
    expect(e.kind).toBe('validation');
    expect(e.fieldErrors).toEqual([{ field: 'title', message: 'must not be blank' }]);
    expect(e.correlationId).toBe('c-1');
  });

  it('maps 409 version conflict, 422 invalid transition, 204 and network failure', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ status: 409, code: 'TICKET_VERSION_CONFLICT', message: 'stale' }, 409));
    await expect(request('/t')).rejects.toMatchObject({ kind: 'version_conflict', status: 409 });
    fetchMock.mockResolvedValueOnce(jsonResponse({ status: 422, code: 'INVALID_TRANSITION', message: 'no' }, 422));
    await expect(request('/t')).rejects.toMatchObject({ kind: 'invalid_transition' });
    fetchMock.mockResolvedValueOnce(jsonResponse(null, 204));
    await expect(request('/t')).resolves.toBeUndefined();
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    await expect(request('/t')).rejects.toMatchObject({ kind: 'network', status: 0 });
  });

  it('copes with non-JSON error bodies', async () => {
    fetchMock.mockResolvedValue(new Response('<html>bad gateway</html>', { status: 502 }));
    await expect(request('/t')).rejects.toMatchObject({ kind: 'server', status: 502 });
  });

  it('401 triggers the global unauthorized handler, except when skipAuthHandling', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    fetchMock.mockResolvedValue(jsonResponse({ status: 401, code: 'UNAUTHENTICATED', message: 'no' }, 401));
    await expect(request('/tickets')).rejects.toMatchObject({ kind: 'unauthenticated' });
    expect(handler).toHaveBeenCalledTimes(1);
    await expect(request('/auth/login', { skipAuthHandling: true })).rejects.toBeInstanceOf(ApiError);
    expect(handler).toHaveBeenCalledTimes(1);
  });
});

describe('helpers', () => {
  it('buildQuery', () => expect(buildQuery({ a: 1, b: '', c: null, d: true })).toBe('?a=1&d=true'));
  it('describeError never leaks internals for server errors', () => {
    expect(describeError(new ApiError(500, 'INTERNAL_ERROR', 'NullPointerException at com.x.Y'))).toBe('The server had a problem. Please try again.');
    expect(describeError(new ApiError(0, 'NETWORK_ERROR', 'x'))).toMatch(/Cannot reach/);
    expect(describeError(new Error('boom'))).toBe('Something went wrong. Please try again.');
    expect(describeError(new ApiError(422, 'INVALID_TRANSITION', 'Cannot go from NEW to CLOSED'))).toBe('Cannot go from NEW to CLOSED');
  });
});
