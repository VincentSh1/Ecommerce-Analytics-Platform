import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Dashboard } from './Dashboard';

const metrics = {
  grossMinor: '20000', refundMinor: '12500', netMinor: '7500', completedCount: '2',
  refundCount: '1', eventCount: '3', averageOrderValueMinor: '10000.00',
  refundEventRatio: '0.500000', eventActivityPerSecond: '0.003333',
};
let data = { ...metrics } as { [K in keyof typeof metrics]: string | null };
let outage = false;
let healthDown = false;
let calls: ReturnType<typeof vi.fn>;

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-09-08T12:15:30.000Z'));
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
  vi.spyOn(Math, 'random').mockReturnValue(0);
  data = { ...metrics }; outage = false; healthDown = false;
  calls = vi.fn(async (input: string) => {
    if (input.includes('/readiness')) return Response.json({ status: healthDown ? 'DOWN' : 'UP' }, { status: healthDown ? 503 : 200 });
    if (outage) return new Response('<html>private backend exception</html>', { status: 503 });
    const params = new URL(input, 'http://test').searchParams;
    return Response.json({ from: params.get('from'), to: params.get('to'), currency: 'USD',
      consistency: 'nonSnapshot', queryStartedAt: new Date().toISOString(), queryCompletedAt: new Date().toISOString(), data });
  });
  vi.stubGlobal('fetch', calls);
});

async function mount() { await act(async () => { render(<Dashboard />); }); }
async function tick(ms: number) { await act(async () => { await vi.advanceTimersByTimeAsync(ms); }); }

describe('dashboard', () => {
  it('omits private readiness requests and the panel when configured for cloud hosting', async () => {
    vi.stubEnv('VITE_READINESS_ENABLED', 'false');
    await mount();
    expect(calls).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('Backend readiness')).not.toBeInTheDocument();
    expect(screen.getByTestId('net-revenue')).toHaveTextContent('$75.00');
    await tick(10000);
    expect(calls).toHaveBeenCalledTimes(2);
    expect(calls.mock.calls.every(([url]) => url.includes('/analytics/summary'))).toBe(true);
  });

  it('renders real response fields with correct labels and minute-aligned parameters', async () => {
    await mount();
    expect(screen.getByTestId('net-revenue')).toHaveTextContent('$75.00');
    expect(screen.getByTestId('completed-count')).toHaveTextContent('2');
    expect(screen.getByText('0.500000 per payment')).toBeInTheDocument();
    expect(screen.getByText('Ready', { exact: true })).toBeInTheDocument();
    expect(calls.mock.calls[0][0]).toContain('from=2026-09-08T12%3A00%3A00.000Z&to=2026-09-08T12%3A15%3A00.000Z');
    expect(screen.getByText(/Last successful query/)).toHaveTextContent('2026-09-08 12:15:30 UTC');
  });

  it('distinguishes a successful empty window from failure and preserves null ratios', async () => {
    data = { grossMinor: '0', refundMinor: '0', netMinor: '0', completedCount: '0', refundCount: '0', eventCount: '0',
      averageOrderValueMinor: null, refundEventRatio: null, eventActivityPerSecond: '0.000000' };
    await mount();
    expect(screen.getByText('No processed events in this window')).toBeInTheDocument();
    expect(screen.getAllByText('—')).toHaveLength(2);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows initial API failure without reporting empty data or leaking response text', async () => {
    outage = true;
    await mount();
    expect(screen.getByRole('alert')).toHaveTextContent('Analytics could not be loaded');
    expect(screen.queryByText('No processed events in this window')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('private backend exception');
    expect(screen.getByText('Ready', { exact: true })).toBeInTheDocument();
  });

  it('polls after ten seconds, retains stale values on failure, backs off, and retries', async () => {
    await mount();
    expect(calls).toHaveBeenCalledTimes(2);
    await tick(9999); expect(calls).toHaveBeenCalledTimes(2);
    data.netMinor = '8000';
    await tick(1); expect(screen.getByTestId('net-revenue')).toHaveTextContent('$80.00');
    outage = true;
    await tick(10000);
    expect(screen.getByRole('alert')).toHaveTextContent('stale results');
    expect(screen.getByTestId('net-revenue')).toHaveTextContent('$80.00');
    await tick(10000);
    expect(screen.getByText(/Automatic retry in about 20 seconds/)).toBeInTheDocument();
    const previousCalls = calls.mock.calls.length;
    await tick(19999); expect(calls).toHaveBeenCalledTimes(previousCalls);
    outage = false;
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Retry now' })); });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('pauses hidden tabs and refreshes on return', async () => {
    await mount();
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
    await tick(30000); expect(calls).toHaveBeenCalledTimes(2);
    hidden.mockReturnValue(false);
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
    expect(calls).toHaveBeenCalledTimes(4);
  });

  it('shows loading, prevents overlap, and cancels old window requests', async () => {
    let resolve!: (value: Response) => void;
    calls.mockImplementation(() => new Promise<Response>(done => { resolve = done; }));
    await mount();
    expect(screen.getByText('Loading analytics…')).toBeInTheDocument();
    await tick(20000); expect(calls).toHaveBeenCalledTimes(2);
    const signal = calls.mock.calls[0][1].signal as AbortSignal;
    await act(async () => { fireEvent.change(screen.getByLabelText('Event-time window'), { target: { value: '60' } }); });
    expect(signal.aborted).toBe(true);
    expect(calls).toHaveBeenCalledTimes(4);
    await act(async () => { resolve(Response.json({ status: 'UP' })); });
  });

  it('shows readiness DOWN independently from available analytics', async () => {
    healthDown = true;
    await mount();
    expect(screen.getByText('Not ready')).toBeInTheDocument();
    expect(screen.getByTestId('net-revenue')).toHaveTextContent('$75.00');
  });
});
