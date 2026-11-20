/** Android UI inspection and taps for the isolated test emulator. */
import { shell, execOut } from './adb.mjs';

await shell(['uiautomator', 'dump', '/sdcard/recordly-window.xml']);
const xml = (await execOut(['cat', '/sdcard/recordly-window.xml'])).toString();
const decode = text => text.replaceAll('&quot;', '"').replaceAll('&amp;', '&')
    .replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&apos;', "'");
const nodes = [...xml.matchAll(/<node\b([^>]*)>/g)].map(match =>
    Object.fromEntries([...match[1].matchAll(/([\w:-]+)="([^"]*)"/g)]
        .map(attribute => [attribute[1], decode(attribute[2])])));
const [action = 'state', target] = process.argv.slice(2);
if (action === 'state') {
    console.log(JSON.stringify(nodes.filter(node => node.text || node['content-desc']).map(node => ({
        text: node.text, description: node['content-desc'], id: node['resource-id'],
        bounds: node.bounds, clickable: node.clickable,
    })), null, 2));
} else if (action === 'tap') {
    const node = nodes.find(node => node.text === target || node['content-desc'] === target ||
        node['resource-id'] === target);
    if (!node) throw new Error(`Android control not found: ${target}`);
    const bounds = node.bounds.match(/\d+/g).map(Number);
    const result = await shell(['input', 'tap', String(Math.round((bounds[0] + bounds[2]) / 2)),
        String(Math.round((bounds[1] + bounds[3]) / 2))]);
    if (result.code) throw new Error(result.stderr);
    console.log(JSON.stringify({ tapped: target, bounds: node.bounds }));
} else {
    throw new Error('Expected state or tap <text, description, or resource id>');
}
