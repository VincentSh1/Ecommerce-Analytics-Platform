import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';

const ingestion = process.env.INGESTION_URL || 'http://127.0.0.1:8081';

test('real pipeline, duplicate effects, outage recovery, and narrow layout', async ({ page, request }) => {
  const browserErrors: string[] = [];
  page.on('pageerror', error => browserErrors.push(error.message));
  // Keep the rolling window stable while KCL assigns leases; timers still run normally.
  await page.clock.setFixedTime(new Date());
  const summaryResponse = page.waitForResponse(response => response.url().includes('/analytics/summary') && response.ok());
  await page.goto('/');
  const baseline = await (await summaryResponse).json();
  await expect(page.getByRole('heading', { name: 'Financial summary' })).toBeVisible();
  const event = {
    schemaVersion: 1, eventId: randomUUID(), orderId: randomUUID(), eventType: 'PAYMENT_COMPLETED',
    occurredAt: new Date(Date.parse(baseline.to) - 60_000).toISOString(), amountMinor: 12345,
    currency: 'USD', productCategory: 'BOOKS', region: 'NA', quantity: 1,
  };
  for (let attempt = 0; attempt < 2; attempt++) {
    const response = await request.post(ingestion + '/api/v1/events', { data: event });
    expect(response.status()).toBe(202);
  }
  const expectedCount = (BigInt(baseline.data.completedCount) + 1n).toLocaleString('en-US');
  await expect(page.getByTestId('completed-count')).toHaveText(expectedCount, { timeout: 300_000 });
  const query = '/api/v1/analytics/summary?' + new URLSearchParams({ from: baseline.from, to: baseline.to });
  const processed = await (await request.get(query)).json();
  expect(BigInt(processed.data.grossMinor) - BigInt(baseline.data.grossMinor)).toBe(12345n);
  expect(BigInt(processed.data.eventCount) - BigInt(baseline.data.eventCount)).toBe(1n);
  // Recheck after another browser poll so the duplicate has an opportunity to surface.
  const refreshed = page.waitForResponse(response => response.url().includes('/analytics/summary') && response.ok());
  await page.getByRole('button', { name: 'Refresh now' }).click();
  await refreshed;
  await expect(page.getByTestId('completed-count')).toHaveText(expectedCount);

  const net = await page.getByTestId('net-revenue').textContent();
  await page.route('**/api/v1/analytics/summary?*', route => route.abort('connectionrefused'));
  await page.getByRole('button', { name: 'Refresh now' }).click();
  await expect(page.getByRole('alert')).toContainText('Showing stale results');
  await expect(page.getByTestId('net-revenue')).toHaveText(net!);
  await page.unroute('**/api/v1/analytics/summary?*');
  await page.getByRole('button', { name: 'Retry now' }).click();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('heading', { name: 'Commerce analytics' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/dashboard-mobile.png', fullPage: true });
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.screenshot({ path: 'test-results/dashboard-desktop.png', fullPage: true });
  expect(browserErrors).toEqual([]);
});
