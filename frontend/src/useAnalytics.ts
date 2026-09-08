import { useEffect, useRef, useState } from 'react';
import { ApiFailure, completeWindow, getReadiness, getSummary, type Readiness, type Summary } from './api';

type Result<T> = { value?: T; error?: ApiFailure; checkedAt?: string };

export function useAnalytics(minutes: number) {
  const readinessEnabled = import.meta.env.VITE_READINESS_ENABLED !== 'false';
  const [summary, setSummary] = useState<Result<Summary>>({});
  const [health, setHealth] = useState<Result<Readiness>>({});
  const [loading, setLoading] = useState(false);
  const [paused, setPaused] = useState(document.hidden);
  const [retryDelay, setRetryDelay] = useState(0);
  const refresh = useRef<() => void>(() => {});

  useEffect(() => {
    let active = true;
    let inFlight = false;
    let failures = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let controller: AbortController | undefined;
    setSummary({});
    setHealth({});
    setRetryDelay(0);

    async function poll() {
      if (!active || document.hidden || inFlight) return;
      clearTimeout(timer);
      inFlight = true;
      setLoading(true);
      const current = new AbortController();
      controller = current;
      let failed = false;
      let retryAfter = 0;
      const window = completeWindow(minutes);
      async function update<T>(request: Promise<T>, setter: React.Dispatch<React.SetStateAction<Result<T>>>) {
        try {
          const value = await request;
          if (active && !current.signal.aborted) setter({ value, checkedAt: new Date().toISOString() });
        } catch (error) {
          if (!active || current.signal.aborted) return;
          const safe = error instanceof ApiFailure ? error : new ApiFailure('NETWORK_ERROR');
          failed = true;
          retryAfter = Math.max(retryAfter, safe.retryAfter);
          setter(previous => ({ ...previous, error: safe }));
        }
      }
      await Promise.all([
        update(getSummary(window, current.signal), setSummary),
        ...(readinessEnabled ? [update(getReadiness(current.signal), setHealth)] : []),
      ]);
      if (!active) return;
      inFlight = false;
      setLoading(false);
      if (document.hidden) return;
      if (current.signal.aborted) { timer = setTimeout(() => void poll(), 0); return; }
      failures = failed ? Math.min(4, failures + 1) : 0;
      const delay = failed
        ? Math.min(60_000, Math.max(retryAfter, 10_000 * 2 ** (failures - 1) * (1 + Math.random() * 0.2)))
        : 10_000;
      setRetryDelay(failed ? Math.ceil(delay / 1000) : 0);
      timer = setTimeout(() => void poll(), delay);
    }
    function visibility() {
      setPaused(document.hidden);
      clearTimeout(timer);
      if (document.hidden) controller?.abort();
      else void poll();
    }
    refresh.current = () => { void poll(); };
    document.addEventListener('visibilitychange', visibility);
    void poll();
    return () => {
      active = false;
      clearTimeout(timer);
      controller?.abort();
      document.removeEventListener('visibilitychange', visibility);
    };
  }, [minutes, readinessEnabled]);

  return { summary, health, loading, paused, retryDelay, readinessEnabled, refresh: () => refresh.current() };
}
