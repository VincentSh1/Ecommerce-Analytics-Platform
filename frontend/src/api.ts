export interface Summary {
  from: string;
  to: string;
  currency: 'USD';
  queryStartedAt: string;
  queryCompletedAt: string;
  consistency: 'nonSnapshot';
  data: {
    grossMinor: string;
    refundMinor: string;
    netMinor: string;
    completedCount: string;
    refundCount: string;
    eventCount: string;
    averageOrderValueMinor: string | null;
    refundEventRatio: string | null;
    eventActivityPerSecond: string;
  };
}

export interface Readiness { status: 'UP' | 'DOWN' }

export class ApiFailure extends Error {
  constructor(public code: string, public requestId?: string, public retryAfter = 0) {
    super(code);
  }
}

const safeCodes = new Set([
  'INVALID_PARAMETER', 'RATE_LIMITED', 'DEPENDENCY_UNAVAILABLE', 'INTERNAL_ERROR',
  'ORIGIN_NOT_ALLOWED', 'NOT_FOUND',
]);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const integer = /^\d{1,64}$/;
const decimal = /^\d{1,64}\.\d{1,6}$/;
const timestamp = (value: unknown): value is string => typeof value === 'string'
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)
  && Number.isFinite(Date.parse(value));
const object = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const matches = (pattern: RegExp, value: unknown) => typeof value === 'string' && pattern.test(value);

// Reject unexpected responses before they can look like valid zero-valued analytics.
export function parseSummary(value: unknown): Summary {
  if (!object(value) || !object(value.data)) throw new ApiFailure('INVALID_RESPONSE');
  const data = value.data;
  if (value.currency !== 'USD' || value.consistency !== 'nonSnapshot'
    || !['from', 'to', 'queryStartedAt', 'queryCompletedAt'].every(key => timestamp(value[key]))
    || !['grossMinor', 'refundMinor', 'completedCount', 'refundCount', 'eventCount'].every(key => matches(integer, data[key]))
    || !matches(/^-?\d{1,64}$/, data.netMinor)
    || !['averageOrderValueMinor', 'refundEventRatio'].every(key => data[key] === null || matches(decimal, data[key]))
    || !matches(decimal, data.eventActivityPerSecond)) throw new ApiFailure('INVALID_RESPONSE');
  return value as unknown as Summary;
}

export function completeWindow(minutes: number, now = Date.now()) {
  const end = Math.floor(now / 60_000) * 60_000;
  return { from: new Date(end - minutes * 60_000).toISOString(), to: new Date(end).toISOString() };
}

function url(path: string) {
  const base = import.meta.env.VITE_API_BASE_URL || '';
  if (!base) return path;
  try {
    const parsed = new URL(base);
    if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password
      || parsed.search || parsed.hash) throw new Error();
    return base.replace(/\/$/, '') + path;
  } catch { throw new ApiFailure('CONFIGURATION_ERROR'); }
}

async function get(path: string, signal: AbortSignal, health = false): Promise<unknown> {
  const timeout = AbortSignal.timeout(12_000);
  try {
    const response = await fetch(url(path), {
      signal: AbortSignal.any([signal, timeout]), credentials: 'omit', cache: 'no-store',
      headers: { Accept: 'application/json' },
    });
    const body: unknown = await response.json().catch(() => null);
    if (health && response.status === 503 && object(body) && body.status === 'DOWN') return body;
    if (!response.ok) {
      const error = object(body) && object(body.error) ? body.error : {};
      const code = typeof error.code === 'string' && safeCodes.has(error.code) ? error.code : 'HTTP_ERROR';
      const id = error.requestId;
      const delay = response.headers.get('Retry-After') || '';
      throw new ApiFailure(code, typeof id === 'string' && uuid.test(id) ? id : undefined,
        /^\d{1,3}$/.test(delay) ? Math.min(60_000, Number(delay) * 1000) : 0);
    }
    return body;
  } catch (error) {
    if (signal.aborted) throw error;
    if (error instanceof ApiFailure) throw error;
    throw new ApiFailure(timeout.aborted ? 'TIMEOUT' : 'NETWORK_ERROR');
  }
}

export async function getSummary(window: { from: string; to: string }, signal: AbortSignal) {
  const result = parseSummary(await get('/api/v1/analytics/summary?' + new URLSearchParams(window), signal));
  if (result.from !== window.from || result.to !== window.to) throw new ApiFailure('INVALID_RESPONSE');
  return result;
}

export async function getReadiness(signal: AbortSignal): Promise<Readiness> {
  const result = await get('/actuator/health/readiness', signal, true);
  if (!object(result) || !['UP', 'DOWN'].includes(String(result.status))) throw new ApiFailure('INVALID_RESPONSE');
  return result as unknown as Readiness;
}

// Monetary strings must never pass through Number, including values above 2^53.
export function dollars(minor: string) {
  const negative = minor.startsWith('-');
  const [whole, fraction] = minor.replace(/^-/, '').split('.');
  const cents = BigInt(whole);
  const extra = fraction && /[1-9]/.test(fraction) ? fraction.replace(/0+$/, '') : '';
  return `${negative ? '-' : ''}$${(cents / 100n).toLocaleString('en-US')}.${String(cents % 100n).padStart(2, '0')}${extra}`;
}

export function count(value: string) { return BigInt(value).toLocaleString('en-US'); }
