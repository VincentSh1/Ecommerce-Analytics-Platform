import { useState } from 'react';
import { count, dollars } from './api';
import { useAnalytics } from './useAnalytics';

function utc(value: string) { return value.replace('T', ' ').replace('.000Z', ' UTC').replace(/\.\d{3}Z$/, ' UTC'); }

export function Dashboard() {
  const [minutes, setMinutes] = useState(15);
  const { summary, health, loading, paused, retryDelay, readinessEnabled, refresh } = useAnalytics(minutes);
  const result = summary.value;
  const data = result?.data;
  const status = health.error ? 'Unavailable' : health.value?.status === 'UP' ? 'Ready'
    : health.value?.status === 'DOWN' ? 'Not ready' : 'Checking';

  return <main>
    <header className="page-header">
      <div><p className="eyebrow">Synthetic commerce · USD</p><h1>Commerce analytics</h1>
        <p className="intro">Financial facts from the event pipeline.</p></div>
      <div className="controls">
        <label htmlFor="window">Event-time window</label>
        <select id="window" value={minutes} onChange={event => setMinutes(Number(event.target.value))}>
          {[15, 30, 60].map(value => <option key={value} value={value}>Last {value} complete minutes</option>)}
        </select>
        <button type="button" onClick={refresh} disabled={loading || paused}>
          {loading ? 'Refreshing…' : summary.error || health.error ? 'Retry now' : 'Refresh now'}
        </button>
      </div>
    </header>

    <div className="refresh-line">
      <p>{paused ? 'Polling paused while this tab is hidden.' : retryDelay
        ? `Automatic retry in about ${retryDelay} seconds after the last attempt.`
        : 'Refreshes every 10 seconds while visible.'}</p>
      <p>Last successful query: {result
        ? <time dateTime={result.queryCompletedAt}>{utc(result.queryCompletedAt)}</time> : 'Not yet available'}</p>
    </div>

    {summary.error && <section className="error" role="alert">
      <h2>Analytics could not be loaded</h2>
      <p>{result ? 'Showing stale results from the last successful query.' : 'No analytics are available. This is not an empty dataset.'} Automatic retries continue while this tab is visible.</p>
      <p className="technical">Code: {summary.error.code}{summary.error.requestId && <> · Request ID: {summary.error.requestId}</>}</p>
      {summary.error.code === 'INVALID_PARAMETER' && <p>Check that the browser clock agrees with the backend clock.</p>}
    </section>}

    <div className="layout">
      <section className="analytics" aria-labelledby="summary-heading" aria-busy={loading}>
        <div className="section-heading"><h2 id="summary-heading">Financial summary</h2>
          {result && <span className={summary.error ? 'badge stale' : 'badge'}>{summary.error ? 'Stale' : 'Non-snapshot reads'}</span>}
        </div>
        {result && <p className="window">{utc(result.from)} → {utc(result.to)}<br />Start included; end excluded. The current minute is not included.</p>}
        {!result && !summary.error && <p role="status" className="placeholder">Loading analytics…</p>}
        {data && <>
          {data.eventCount === '0' && <div className="empty" role="status"><h3>No processed events in this window</h3>
            <p>The query succeeded with zero financial facts. Pending events may still be processing.</p></div>}
          <dl className="revenue">
            <div><dt>Net revenue</dt><dd data-testid="net-revenue">{dollars(data.netMinor)}</dd></div>
            <div><dt>Gross revenue</dt><dd>{dollars(data.grossMinor)}</dd></div>
            <div><dt>Refund amount</dt><dd>{dollars(data.refundMinor)}</dd></div>
          </dl>
          <table>
            <caption>Window activity</caption>
            <tbody>
              <tr><th scope="row">Completed payments</th><td data-testid="completed-count">{count(data.completedCount)}</td></tr>
              <tr><th scope="row">Average order value</th><td>{data.averageOrderValueMinor === null ? '—' : dollars(data.averageOrderValueMinor)}</td></tr>
              <tr><th scope="row">Refund events</th><td>{count(data.refundCount)}</td></tr>
              <tr><th scope="row">Refund-to-payment ratio</th><td>{data.refundEventRatio === null ? '—' : `${data.refundEventRatio} per payment`}</td></tr>
              <tr><th scope="row">Unique processed facts</th><td data-testid="event-count">{count(data.eventCount)}</td></tr>
              <tr><th scope="row">Event-time activity</th><td>{data.eventActivityPerSecond} events/s</td></tr>
            </tbody>
          </table>
          <p className="note">— means there are no completed payments to use as a denominator. Fractional cents in average order value are preserved.</p>
        </>}
      </section>

      <aside aria-label="Operational context">
        {readinessEnabled && <>
        <h2 id="health-heading">Backend readiness</h2>
        <p>Analytics Service</p>
        <p className={`health ${status === 'Ready' ? 'ready' : 'unavailable'}`} role="status">{status}</p>
        {health.error && <p role="alert">Readiness could not be checked. Code: {health.error.code}</p>}
        {health.checkedAt && <p className="note">Last successful check:<br /><time dateTime={health.checkedAt}>{utc(health.checkedAt)}</time></p>}
        <p className="note">Readiness checks the scheduler and its dependencies. It does not prove the consumer is caught up. Stream lag is not exposed by this API.</p>
        <hr />
        </>}
        <h2>Reading these metrics</h2>
        <p>Totals use event occurrence time, not ingestion or processing time. Late events can change a previous window.</p>
        <p>Completed payments count unique payment facts, not independently verified orders. Refund ratio is window activity, not a cohort refund rate, and can exceed one.</p>
        <p>Event-time activity is not measured processing throughput. Reads do not form a global snapshot.</p>
      </aside>
    </div>
    <footer>Local operational dashboard · Synthetic data only · AWS deployment and throughput benchmarking remain unproven.</footer>
  </main>;
}
