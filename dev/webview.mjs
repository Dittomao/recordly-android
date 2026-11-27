/** Inspect and exercise the debug app's WebView through its Android DevTools socket. */
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { appProcessId, forward } from './adb.mjs';

const require = createRequire(import.meta.url);
const { chromium } = require('../recordly-upstream/node_modules/playwright');
const processId = await appProcessId('app.tapswoop.android.debug');
if (!processId) throw new Error('Recordly debug app is not running');
await forward(9222, `localabstract:webview_devtools_remote_${processId}`);
const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
try {
    const page = browser.contexts().flatMap(context => context.pages())
        .find(page => page.url().startsWith('http://127.0.0.1:'));
    if (!page) throw new Error('No Recordly renderer page found');
    const [action = 'state', argument] = process.argv.slice(2);
    let result;
    if (action === 'state') {
        result = await page.evaluate(() => ({
            url: location.href, width: innerWidth, height: innerHeight,
            text: document.body.innerText.slice(0, 10_000),
            buttons: [...document.querySelectorAll('button')].map(button => ({
                text: button.innerText, label: button.getAttribute('aria-label'),
                title: button.getAttribute('title'), role: button.getAttribute('role'),
                disabled: button.disabled,
            })),
        }));
    } else if (action === 'eval') {
        result = await page.evaluate(await readFile(argument, 'utf8'));
    } else if (action === 'click') {
        await page.locator(argument).click({ timeout: 10_000 });
        result = { clicked: argument };
    } else if (action === 'button') {
        await page.getByRole('button', { name: argument, exact: true }).click({ timeout: 10_000 });
        result = { clicked: argument };
    } else if (action === 'text') {
        await page.getByText(argument, { exact: true }).click({ timeout: 10_000 });
        result = { clicked: argument };
    } else {
        throw new Error('Expected state, eval <file>, click <selector>, button <name>, or text <text>');
    }
    console.log(JSON.stringify(result, null, 2));
} finally {
    await browser.close();
}
