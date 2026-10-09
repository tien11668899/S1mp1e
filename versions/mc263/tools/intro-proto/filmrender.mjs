// Film render: drive film.html over CDP and stream every frame (PNG) into ffmpeg -> ProRes 422 HQ.
// usage: CDP_PORT=9334 node filmrender.mjs <pageUrl> <frames.json> <out.mov> [fps]
//   frames.json = [[t, zoom, sx, sy], ...]  (one entry per output frame)
import fs from 'node:fs';
import { spawn } from 'node:child_process';
const [,, pageUrl, framesPath, outMov, fpsArg] = process.argv;
const PORT = process.env.CDP_PORT || 9334;
const fps = +(fpsArg || 60);
const frames = JSON.parse(fs.readFileSync(framesPath, 'utf8'));
const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const page = targets.find(t => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0; const pending = new Map();
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } };
await new Promise(r => ws.onopen = r);
const send = (method, params = {}) => new Promise(r => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const evalJs = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
  if (r.result?.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails).slice(0, 600));
  return r.result?.result?.value;
};
await send('Page.enable');
await send('Page.navigate', { url: pageUrl + (pageUrl.includes('?') ? '&' : '?') + 'nocache=' + Date.now() });
for (let k = 0; k < 300; k++) { if (await evalJs('window.READY === true || !!window.ERR').catch(() => false)) break; await new Promise(r => setTimeout(r, 100)); }
const err = await evalJs('window.ERR || null');
if (err) throw new Error('page error: ' + err);
const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(fps), '-c:v', 'png', '-i', '-',
  '-c:v', 'prores_ks', '-profile:v', '3', '-vendor', 'apl0', '-pix_fmt', 'yuv422p10le',
  '-color_primaries', 'bt709', '-color_trc', 'bt709', '-colorspace', 'bt709', outMov], { stdio: ['pipe', 'inherit', 'inherit'] });
const t0 = Date.now();
for (let n = 0; n < frames.length; n++) {
  const [t, z, sx, sy] = frames[n];
  const url = await evalJs(`(async()=>{ await renderAt(${t}, ${z ?? 1}, ${sx ?? 0}, ${sy ?? 0}); return document.getElementById('c').toDataURL('image/png'); })()`);
  const buf = Buffer.from(url.split(',')[1], 'base64');
  if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
  if (n % 60 === 0) console.log(`frame ${n}/${frames.length} ${((Date.now() - t0) / 1000).toFixed(0)}s`);
}
ff.stdin.end();
await new Promise(r => ff.on('close', r));
console.log(`done ${frames.length} frames -> ${outMov}`);
ws.close();
