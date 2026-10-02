// Drive a headless Chrome over CDP: open a page, call renderAt(t) for each t, save the canvas as PNG.
// usage: node render.mjs <pageUrl> <outDir> <prefix> <t0> <t1> <fps>   |   ... <prefix> list 0.1,0.5,...
import fs from 'node:fs';
import path from 'node:path';
const [,, pageUrl, outDir, prefix, a, b, c] = process.argv;
const PORT = process.env.CDP_PORT || 9333;
let times = [];
if (a === 'list') times = b.split(',').map(Number);
else { const t0 = +a, t1 = +b, fps = +c; for (let i = 0; t0 + i / fps <= t1 + 1e-9; i++) times.push(+(t0 + i / fps).toFixed(4)); }
fs.mkdirSync(outDir, { recursive: true });
const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const page = targets.find(t => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0; const pending = new Map();
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } };
await new Promise(r => ws.onopen = r);
const send = (method, params = {}) => new Promise(r => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const evalJs = async (expr) => { const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true }); if (r.result?.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails).slice(0, 600)); return r.result?.result?.value; };
await send('Page.enable');
await send('Page.navigate', { url: pageUrl + (pageUrl.includes('?') ? '&' : '?') + 'nocache=' + Date.now() });
for (let k = 0; k < 200; k++) { if (await evalJs('window.READY === true').catch(() => false)) break; await new Promise(r => setTimeout(r, 100)); }
if (!(await evalJs('window.READY === true'))) throw new Error('page never READY');
let n = 0;
for (const t of times) {
  const url = await evalJs(`(async()=>{ await renderAt(${t}); return document.getElementById('c').toDataURL('image/png'); })()`);
  fs.writeFileSync(path.join(outDir, `${prefix}_${String(n++).padStart(4, '0')}_${t.toFixed(3)}.png`), Buffer.from(url.split(',')[1], 'base64'));
}
console.log(`rendered ${n} frames`);
ws.close();
