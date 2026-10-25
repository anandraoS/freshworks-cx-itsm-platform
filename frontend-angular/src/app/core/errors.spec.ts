import { ApiError, describeError, isKind, mapKind } from './errors';

describe('error mapping', () => {
  it('maps contract codes to kinds', () => {
    expect(mapKind(409, 'TICKET_VERSION_CONFLICT')).toBe('version_conflict');
    expect(mapKind(409, 'DUPLICATE_SUBMISSION')).toBe('duplicate');
    expect(mapKind(422, 'INVALID_TRANSITION')).toBe('invalid_transition');
    expect(mapKind(503, 'AI_UNAVAILABLE')).toBe('ai_unavailable');
    expect(mapKind(400, 'VALIDATION_FAILED')).toBe('validation');
  });

  it('falls back to the HTTP status when the code is unknown or missing', () => {
    expect(mapKind(0, '')).toBe('network');
    expect(mapKind(401, '')).toBe('unauthenticated');
    expect(mapKind(403, '')).toBe('forbidden');
    expect(mapKind(404, 'WHATEVER')).toBe('not_found');
    expect(mapKind(429, '')).toBe('rate_limited');
    expect(mapKind(502, '')).toBe('server');
    expect(mapKind(418, '')).toBe('unknown');
  });

  it('describeError never leaks internals for non-API errors', () => {
    expect(describeError(new Error('NullPointerException at com.example'))).toBe('Something went wrong. Please try again.');
  });

  it('describeError uses friendly text for forbidden/network/server and the API message otherwise', () => {
    expect(describeError(new ApiError(403, 'FORBIDDEN', 'raw'))).toContain('permission');
    expect(describeError(new ApiError(0, 'NETWORK_ERROR', 'Network error'))).toContain('Cannot reach');
    expect(describeError(new ApiError(500, 'INTERNAL_ERROR', 'stack trace here'))).not.toContain('stack');
    expect(describeError(new ApiError(409, 'TICKET_VERSION_CONFLICT', 'Version is stale'))).toBe('Version is stale');
  });

  it('isKind narrows', () => {
    expect(isKind(new ApiError(404, 'NOT_FOUND', 'x'), 'not_found')).toBeTrue();
    expect(isKind(new Error('x'), 'not_found')).toBeFalse();
  });
});
