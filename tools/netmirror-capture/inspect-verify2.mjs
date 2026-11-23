// Test the hypothesis: after the gate passes, does verify2.php itself (or where it redirects)
// start serving the HOME catalog from the SAME api? Load it with the valid session, let it fully
// settle, and capture: every /mobile/* XHR it fires, the final rendered card DOM, any redirect,
// and any endpoint URLs baked into its inline scripts.
//
// Usage: node inspect-verify2.mjs [--session cdp-capture/session.txt] [--cookie-host net52.cc]

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const args = Object.fromEntries(process.argv.slice(2).reduce((a, v, i, arr) => {
  if (v.startsWith('--')) a.push([v.slice(2), arr[i + 1]]); return a;
}, []));
const HOST = args['cookie-host'] || 'net52.cc';
const SESSION = path.resolve(args.session || path.join(__dirname, 'cdp-capture', 'session.txt'));
const PORT = Number(args.port || 9225);
const UA = process.env.MIRROR_BROWSER_UA || args.ua ||
  'Mozilla/5.0 (Linux; Android 10; Core-T4) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36';
const CHROME = [args.chrome, process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  (process.env.LOCALAPPDATA || '') + '/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find(p => p && fs.existsSync(p));
if (!CHROME) { console.error('Chrome not found.'); process.exit(1); }
if (!fs.existsSync(SESSION)) { console.error('No session at', SESSION); process.exit(1); }

const cookies = {};
for (const part of fs.readFileSync(SESSION, 'utf8').split(';')) {
  const i = part.indexOf('='); if (i < 0) continue;
  cookies[part.slice(0, i).trim()] = part.slice(i + 1).trim();
}
const t = (cookies.t_hash_t || '').split('::')[0];
const red = s => t ? String(s).split(t).join('{{t}}') : String(s);

const PROFILE = path.join(os.tmpdir(), 'nm-v2-' + Date.now());
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[v2]', ...a);
const chrome = spawn(CHROME, [`--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--headless=new', '--disable-gpu', 'about:blank'],
  { detached: false, stdio: 'ignore' });

class CDP {
  constructor(ws) { this.ws = new WebSocket(ws); this.id = 1; this.pending = new Map(); this.handlers = []; }
  open() { return new Promise((res, rej) => { this.ws.addEventListener('open', res); this.ws.addEventListener('error', rej);
    this.ws.addEventListener('message', ev => { const m = JSON.parse(ev.data);
      if (m.id && this.pending.has(m.id)) { this.pending.get(m.id)(m); this.pending.delete(m.id); }
      else if (m.method) for (const h of this.handlers) h(m); }); }); }
  on(fn) { this.handlers.push(fn); }
  send(method, params = {}) { const id = this.id++; this.ws.send(JSON.stringify({ id, method, params })); return new Promise(r => this.pending.set(id, r)); }
}

async function main() {
  let v; for (let i = 0; i < 40; i++) { try { v = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); break; } catch { await sleep(500); } }
  log('connected:', v.Browser);
  const pageWs = (await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()).find(t => t.type === 'page');
  const page = new CDP(pageWs.webSocketDebuggerUrl); await page.open();
  await page.send('Network.enable'); await page.send('Page.enable'); await page.send('Runtime.enable');
  await page.send('Network.setUserAgentOverride', { userAgent: UA });
  for (const [name, value] of Object.entries(cookies))
    for (const domain of [HOST, '.' + HOST])
      await page.send('Network.setCookie', { name, value, domain, path: '/', secure: true }).catch(() => {});

  const net = [];
  page.on(m => {
    if (m.method === 'Network.requestWillBeSent') net.push({ phase: 'req', url: red(m.params.request.url), method: m.params.request.method });
    if (m.method === 'Network.responseReceived') net.push({ phase: 'resp', url: red(m.params.response.url.split('?')[0]), status: m.params.response.status, type: m.params.type, mime: m.params.response.mimeType });
    if (m.method === 'Page.frameNavigated' && !m.params.frame.parentId) net.push({ phase: 'nav', url: red(m.params.frame.url) });
  });
  const evalIn = async expr => {
    const r = await page.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
    if (r.result?.exceptionDetails) throw new Error(r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text);
    return r.result?.result?.value;
  };

  log('navigating to verify2.php and letting it settle for 10s…');
  await page.send('Page.navigate', { url: `https://${HOST}/mobile/verify2.php` });
  await sleep(10000); // long settle — catch any lazy catalog load or redirect

  const dom = await evalIn(`(()=>{
    const cards=[...document.querySelectorAll('a[href*="id="],a[href*="post"],[data-id],.card,.poster,.movie,.item,.content')];
    const imgs=[...document.images].map(i=>i.src).filter(Boolean);
    const hosts={}; imgs.forEach(s=>{try{const h=new URL(s).host;hosts[h]=(hosts[h]||0)+1;}catch(e){}});
    const headings=[...document.querySelectorAll('h1,h2,h3,h4,.heading,.row-title,.section-title,.title')].map(h=>h.textContent.trim()).filter(Boolean).slice(0,15);
    // pull endpoint-ish strings out of inline scripts
    const urls=new Set();
    for(const s of document.scripts){const txt=s.textContent||'';for(const m of txt.matchAll(/["'\\\`]([\\/a-z0-9_\\-]+\\.php[^"'\\\`]*)["'\\\`]/gi))urls.add(m[1]);
      for(const m of txt.matchAll(/(?:url|href|action|api|endpoint)\\s*[:=]\\s*["'\\\`]([^"'\\\`]+)["'\\\`]/gi))urls.add(m[1]);}
    return {loc:location.href, title:document.title, readyState:document.readyState,
      bodyLen:document.body?document.body.innerHTML.length:0, cardCount:cards.length,
      imgHosts:hosts, headings, scriptUrls:[...urls].slice(0,40),
      bodyText:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').slice(0,600)};
  })()`);

  const seen = [...new Map(net.map(x => [x.phase + x.url + (x.status || ''), x])).values()];
  log('final location :', dom.loc);
  log('title          :', dom.title, '| cards:', dom.cardCount, '| bodyLen:', dom.bodyLen);
  log('img hosts      :', JSON.stringify(dom.imgHosts));
  log('headings       :', JSON.stringify(dom.headings));
  log('body text      :', dom.bodyText.slice(0, 220));
  log('--- /mobile/ & nav traffic verify2 fired on its own ---');
  for (const x of seen.filter(x => x.phase === 'nav' || /\/mobile\//.test(x.url)))
    log(`   ${x.phase} ${x.status || x.method || ''} ${x.url} ${x.mime || ''}`);
  log('--- endpoint strings in inline scripts ---');
  for (const u of dom.scriptUrls) log('   script:', u);

  const outPath = path.join(__dirname, 'cdp-capture', `inspect-verify2-${new Date().toISOString().slice(0, 10)}.json`);
  fs.writeFileSync(outPath, JSON.stringify({ host: HOST, dom, traffic: seen }, null, 2));
  log('saved ->', outPath);
  try { await page.send('Browser.close'); } catch {}
  try { chrome.kill(); } catch {}
  await sleep(600);
  try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch {}
  process.exit(0);
}
main().catch(async e => { console.error('ERROR:', e.message || e); try { chrome.kill(); } catch {}; await sleep(800); try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch {}; process.exit(1); });
