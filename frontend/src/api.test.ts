import { describe, expect, it, vi } from 'vitest';
import { completeWindow, count, dollars, getSummary, parseSummary } from './api';

describe('API boundary and exact formatting', () => {
  it('preserves large amounts, negative net, and fractional cents without Number conversion', () => {
    expect(dollars('9007199254740993')).toBe('$90,071,992,547,409.93');
    expect(dollars('-12500')).toBe('-$125.00');
    expect(dollars('0.50')).toBe('$0.005');
    expect(dollars('10000.00')).toBe('$100.00');
    expect(count('9007199254740993')).toBe('9,007,199,254,740,993');
  });
  it('handles a window crossing UTC midnight', () => {
    expect(completeWindow(15, Date.parse('2026-09-08T00:04:59.999Z'))).toEqual({
      from: '2026-09-07T23:49:00.000Z', to: '2026-09-08T00:04:00.000Z',
    });
  });
  it('rejects malformed success bodies rather than inventing zero values', () => {
    expect(() => parseSummary({ data: {} })).toThrow('INVALID_RESPONSE');
  });
  it('exposes only allowlisted errors, validated request IDs, and bounded retry delays', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ error: {
      code: 'DEPENDENCY_UNAVAILABLE', requestId: 'd5f212aa-4323-423a-83be-7028a48e0ef1', message: 'private stack trace',
    } }, { status: 503, headers: { 'Retry-After': '45' } })));
    await expect(getSummary(completeWindow(15), new AbortController().signal)).rejects.toMatchObject({
      code: 'DEPENDENCY_UNAVAILABLE', requestId: 'd5f212aa-4323-423a-83be-7028a48e0ef1', retryAfter: 45000,
    });
  });
  it('tolerates network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('internal detail')));
    await expect(getSummary(completeWindow(15), new AbortController().signal)).rejects.toThrow('NETWORK_ERROR');
  });
});
