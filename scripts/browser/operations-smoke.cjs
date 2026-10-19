'use strict';
// Uses installed Playwright + Chromium. Every domain request is GET; only login/logout may write.
const fs = require('fs');
const path = require('path');
let playwright;
try { playwright = require('playwright'); }
catch { playwright = require('/opt/codex/cua_node/lib/node_modules/playwright'); }

const root = path.resolve(__dirname, '../..');
const api = process.env.CHARGEHUB_API_URL || 'http://127.0.0.1:8080';
const password = process.env.CHARGEHUB_DEMO_PASSWORD;
const overlay = process.env.CHARGEHUB_UI_SOURCE_OVERLAY === '1';
const reportPath = path.resolve(root, process.env.CHARGEHUB_UI_REPORT || '.local/reports/operations-ui.json');
const redact = value => String(value).split(password || '\0').join('[REDACTED]');
const report = { simulated: true, execution: 'real-browser-http',
  simulationScope: 'Local payment and device channels; browser and HTTP responses are real',
  api, executedAt: new Date().toISOString(), sourceOverlay: overlay,
  businessWrites: 0, roles: [], pageErrors: [], consoleErrors: [], requests: [], blockedWrites: [] };
const assert = (condition, message) => { if (!condition) throw Error(message); };

async function main() {
  if (!password) throw Error('CHARGEHUB_DEMO_PASSWORD must be injected; credentials are never printed');
  fs.mkdirSync(path.dirname(reportPath), { recursive: true });
  const browser = await playwright.chromium.launch({ executablePath: process.env.CHARGEHUB_BROWSER_PATH || '/usr/bin/chromium',
    headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  report.browserVersion = await browser.version();
  try {
    for (const username of ['ops', 'finance', 'admin', 'customer']) {
      const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } });
      const page = await context.newPage();
      const checks = [];
      page.on('pageerror', error => report.pageErrors.push({ username, message: redact(error.message) }));
      page.on('console', message => { if (message.type() === 'error') report.consoleErrors.push({ username, message: redact(message.text()) }); });
      page.on('response', response => {
        const url = new URL(response.url());
        if (url.pathname.startsWith('/v1/')) report.requests.push({ username, path: url.pathname, method: response.request().method(), status: response.status() });
      });
      await page.route('**/v1/**', async route => {
        const request = route.request(), url = new URL(request.url());
        if (!['GET', 'HEAD'].includes(request.method()) && !['/v1/auth/token', '/v1/auth/logout'].includes(url.pathname)) {
          report.blockedWrites.push({ username, path: url.pathname, method: request.method() });
          await route.abort('blockedbyclient');
          return;
        }
        // Read real existing rows one at a time to exercise pagination without creating synthetic fixtures.
        if (username === 'admin' && url.pathname === '/v1/admin/charge-orders') {
          url.searchParams.set('limit', '1');
          await route.continue({ url: url.toString() });
          return;
        }
        await route.continue();
      });
      if (overlay) await page.route('**/operations.js', route => route.fulfill({
        path: path.join(root, 'chargehub-app/src/main/resources/static/operations.js'), contentType: 'application/javascript' }));
      await page.goto(api + '/operations.html', { waitUntil: 'networkidle' });
      if (username === 'ops') {
        report.amountFormatting = await page.evaluate(() => ({ missing: amount(null), absent: amount(undefined), empty: amount(''), zero: amount(0), positive: amount(123), negative: amount(-123) }));
        const expected = { missing: '—', absent: '—', empty: '—', zero: '¥0.00', positive: '¥1.23', negative: '¥-1.23' };
        assert(JSON.stringify(report.amountFormatting) === JSON.stringify(expected), 'Missing finance amounts must remain distinct from zero');
      }
      await page.fill('#username', username);
      await page.fill('#password', password);
      const loginResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/v1/auth/token');
      const deniedLogout = username === 'customer' ? page.waitForResponse(response => new URL(response.url()).pathname === '/v1/auth/logout') : null;
      await page.click('#loginButton');
      const login = await loginResponse;
      assert(login.status() === 200, username + ' login did not succeed');
      await page.waitForFunction(() => document.querySelector('#password').value === '');
      assert(await page.inputValue('#password') === '', username + ' password input retained credentials');
      checks.push('Real login succeeds and clears password input');
      if (username === 'customer') {
        const logout = await deniedLogout;
        assert(logout.status() === 200, 'Rejected nonstaff token was not revoked');
        await page.waitForFunction(() => staff.token === null);
        assert(!await page.locator('#staffArea').isVisible(), 'Customer entered staff workspace');
        assert(await page.locator('#staffLogin').isVisible(), 'Customer rejection hid login');
        checks.push('CUSTOMER rejected from staff workspace and token automatically revoked');
      } else {
        await page.locator('#staffArea').waitFor({ state: 'visible' });
        await page.waitForFunction(() => document.querySelector('#orderRows').textContent.trim().length > 0);
        const initialOrderCount = await page.evaluate(() => staff.orders.length);
        if (initialOrderCount === 0) assert(await page.locator('#orderRows .empty').isVisible(), 'Empty order list did not show its empty state');
        checks.push(initialOrderCount === 0 ? 'Empty filtered order list renders an explicit safe empty state' : 'Initial filtered order list renders existing rows');
        assert(await page.locator('#finance').isVisible() === (username !== 'ops'), 'Finance visibility mismatch for ' + username);
        assert(await page.locator('#outbox').isVisible() === (username === 'admin'), 'Outbox visibility mismatch for ' + username);
        assert(await page.locator('#audits').isVisible() === (username !== 'ops'), 'Audit visibility mismatch for ' + username);
        checks.push('Role-specific finance, Outbox and audit sections match permissions');
        await page.selectOption('#orderStatus', '');
        await Promise.all([page.waitForResponse(response => new URL(response.url()).pathname === '/v1/admin/charge-orders'), page.click('#filterOrders')]);
        await page.waitForFunction(() => !document.querySelector('#filterOrders').disabled);
        if (username === 'admin' && await page.locator('#moreOrders').isVisible()) {
          const before = await page.evaluate(() => staff.orders.length);
          await Promise.all([page.waitForResponse(response => new URL(response.url()).pathname === '/v1/admin/charge-orders'), page.click('#moreOrders')]);
          await page.waitForFunction(size => staff.orders.length > size, before);
          assert(await page.evaluate(() => new Set(staff.orders.map(row => row.id)).size === staff.orders.length), 'Staff pagination repeated rows');
          checks.push('Load-more appends distinct real rows using nextCursor (read page size1)');
        }
        const contractReport = path.join(root, '.local/reports/contracts-staff.json');
        let meterOrder = process.env.CHARGEHUB_UI_METER_ORDER || null;
        if (!meterOrder && fs.existsSync(contractReport)) {
          const existing = JSON.parse(fs.readFileSync(contractReport, 'utf8')).checks.find(row => row.httpStatus === 200 && row.path.endsWith('/meter-review'));
          if (existing) meterOrder = existing.path.split('/')[4];
        }
        if (meterOrder) {
          await page.fill('#meterOrder', meterOrder);
          const result = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/meter-review'));
          await page.click('#loadMeter');
          assert((await result).status() === 200, 'Meter evidence did not load');
          await page.locator('#meterEvidence .info').waitFor();
          checks.push('Existing authenticated meter evidence and policy render without issuing decisions');
        } else checks.push('Meter GET skipped: no existing order with device session selected');
        if (username !== 'ops') {
          await Promise.all([page.waitForResponse(response => new URL(response.url()).pathname === '/v1/admin/reconciliation-items'), page.click('#refreshFinance')]);
          await page.waitForFunction(() => !document.querySelector('#refreshFinance').disabled);
          assert((await page.locator('#financeRows').innerText()).trim().length > 0, 'Finance list did not render');
          const financeCount = await page.evaluate(() => staff.finance.length);
          if (financeCount === 0) assert(await page.locator('#financeRows .empty').isVisible(), 'Empty finance list did not show its empty state');
          checks.push(financeCount === 0 ? 'Empty finance list renders an explicit safe empty state' : 'Existing finance differences render from real API');
          await Promise.all([page.waitForResponse(response => new URL(response.url()).pathname === '/v1/admin/audits'), page.click('#refreshAudits')]);
          await page.waitForFunction(() => !document.querySelector('#refreshAudits').disabled);
          checks.push('Read-only audit list renders');
        }
        if (username === 'admin') {
          await Promise.all([page.waitForResponse(response => new URL(response.url()).pathname === '/v1/admin/outbox'), page.click('#refreshOutbox')]);
          await page.waitForFunction(() => !document.querySelector('#refreshOutbox').disabled);
          checks.push('DEAD Outbox list or explicit empty state renders');
          await page.selectOption('#outboxStatus', 'DONE');
          await Promise.all([page.waitForResponse(response => new URL(response.url()).pathname === '/v1/admin/outbox'), page.click('#refreshOutbox')]);
          await page.waitForFunction(() => !document.querySelector('#refreshOutbox').disabled);
          assert(await page.locator('[data-replay]:enabled').count() === 0, 'Completed tasks exposed a replay action');
          checks.push('DONE tasks render with replay disabled');
          await page.screenshot({ path: path.join(path.dirname(reportPath), 'operations-admin.png'), fullPage: true });
        }
        assert(!await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), 'Desktop horizontal page overflow');
        const previousToken = await page.evaluate(() => staff.token);
        const logoutResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/v1/auth/logout');
        await page.click('#logout');
        assert((await logoutResponse).status() === 200, username + ' logout failed');
        await page.locator('#staffLogin').waitFor({ state: 'visible' });
        const oldSession = await context.request.get(api + '/v1/auth/me', { headers: { Authorization: 'Bearer ' + previousToken } });
        assert(oldSession.status() === 401, 'Logged-out token remained usable');
        checks.push('Logout returns to login and previous token is rejected401');
      }
      report.roles.push({ username, checks });
      await context.close();
    }
    assert(report.pageErrors.length === 0, 'Browser JavaScript page errors occurred');
    assert(report.consoleErrors.length === 0, 'Browser console errors occurred');
    assert(report.blockedWrites.length === 0, 'UI unexpectedly attempted a domain write');
    assert(!report.requests.some(request => request.status >= 400), 'A browser API request failed unexpectedly');
    report.status = 'PASS';
  } finally {
    await browser.close();
    fs.writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n');
  }
  console.log(JSON.stringify({ status: report.status, roleCount: report.roles.length, pageErrors: report.pageErrors.length,
    consoleErrors: report.consoleErrors.length, businessWrites: report.businessWrites, sourceOverlay: overlay, report: reportPath }));
}
main().catch(error => {
  report.status = 'FAIL'; report.failure = redact(error.message);
  fs.mkdirSync(path.dirname(reportPath), { recursive: true });
  fs.writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n');
  console.error(report.failure); process.exit(1);
});
