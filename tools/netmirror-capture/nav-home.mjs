// Load home the way the APP does: a full-page NAVIGATION after the gate (not an XHR).
// TokenManager scrapes home?app=1 as HTML — so we navigate to it, let its own scripts run,
// capture every /mobile/* XHR it fires and the rendered card DOM. Per OTT skin.
//
// Usage: node nav-home.mjs [--session cdp-capture/session.txt] [--cookie-host net52.cc]

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
const PORT = Number(args.port || 9224);
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

const PROFILE = path.join(os.tmpdir(), 'nm-nav-' + Date.now());
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[nav]', ...a);
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
  await page.send('Network.enable');
  await page.send('Page.enable');
  await page.send('Network.setUserAgentOverride', { userAgent: UA });
  for (const [name, value] of Object.entries(cookies))
    for (const domain of [HOST, '.' + HOST])
      await page.send('Network.setCookie', { name, value, domain, path: '/', secure: true }).catch(() => {});

  const xhrs = [];
  page.on(m => {
    if (m.method === 'Network.responseReceived') {
      const u = m.params.response.url;
      if (/\/mobile\//.test(u)) xhrs.push({ url: u.split('?')[0], type: m.params.type, status: m.params.response.status, mime: m.params.response.mimeType });
    }
  });
  const evalIn = async expr => {
    const r = await page.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
    if (r.result?.exceptionDetails) throw new Error(r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text);
    return r.result?.result?.value;
  };

  // prime the session on the origin first
  await page.send('Page.navigate', { url: `https://${HOST}/mobile/verify2.php` }); await sleep(3000);

  const report = { host: HOST, candidates: {}, skins: [] };

  // First: try the exact app entry as a NAVIGATION (document load), a few URL shapes.
  for (const url of [`https://${HOST}/mobile/home?app=1`, `https://${HOST}/mobile/home`, `https://${HOST}/mobile/`, `https://${HOST}/mobile/index.php`]) {
    xhrs.length = 0;
    await page.send('Page.navigate', { url }); await sleep(4000);
    const info = await evalIn(`(()=>{
      const cards=[...document.querySelectorAll('a[href*="post"],a[href*="id="],[data-id],[onclick*="post"],.card,.poster,.movie,.content,.item')];
      const imgs=[...document.images].map(i=>i.src).filter(Boolean);
      const hosts={}; imgs.forEach(s=>{try{const h=new URL(s).host;hosts[h]=(hosts[h]||0)+1;}catch(e){}});
      const headings=[...document.querySelectorAll('h1,h2,h3,.heading,.row-title,.section-title')].map(h=>h.textContent.trim()).filter(Boolean).slice(0,10);
      return {loc:location.href, title:document.title, bodyLen:document.body?document.body.innerHTML.length:0,
        cardCount:cards.length, imgHosts:hosts, headings,
        notFound:/page not found|file not found/i.test(document.body?document.body.innerText:'')};
    })()`);
    report.candidates[url] = { ...info, xhrs: [...new Map(xhrs.map(x => [x.url, x])).values()] };
    log(`NAV ${url} -> ${info.loc} | cards=${info.cardCount} imgs=${JSON.stringify(info.imgHosts)} notFound=${info.notFound} xhrs=${report.candidates[url].xhrs.length}`);
    for (const h of info.headings) log(`      heading: ${h}`);
  }

  // If a navigable home exists, sweep it per skin.
  const homeUrl = Object.entries(report.candidates).find(([, i]) => !i.notFound && i.cardCount > 0)?.[0];
  if (homeUrl) {
    log('HOME FOUND:', homeUrl, '— sweeping per skin');
    for (const ott of ['nf', 'pv', 'hs']) {
      await evalIn(`fetch("/mobile/setting.php",{method:"POST",headers:{"X-Requested-With":"XMLHttpRequest","Content-Type":"application/x-www-form-urlencoded; charset=UTF-8"},body:"ott=${ott}"}).then(r=>r.text())`);
      xhrs.length = 0;
      await page.send('Page.navigate', { url: homeUrl }); await sleep(4000);
      const dom = await evalIn(`(()=>{
        const out=[];
        for(const a of document.querySelectorAll('a[href*="id="],a[href*="post"],[data-id]')){
          const href=a.getAttribute('href')||''; const m=href.match(/id=(\\d+)/)||[]; const id=a.getAttribute('data-id')||m[1];
          const img=a.querySelector('img'); const title=(a.getAttribute('title')||a.textContent||'').trim().slice(0,80);
          if(id) out.push({id, poster:img?img.src:null, title});
        }
        return out.slice(0,60);
      })()`);
      report.skins.push({ ott, cardCount: dom.length, cards: dom.slice(0, 8), xhrs: [...new Map(xhrs.map(x => [x.url, x])).values()] });
      log(`  skin ${ott}: ${dom.length} cards`);
    }
  } else {
    log('No navigable catalog home among the candidates — the web build has no home page.');
  }

  const outPath = path.join(__dirname, 'cdp-capture', `nav-home-${new Date().toISOString().slice(0, 10)}.json`);
  fs.writeFileSync(outPath, JSON.stringify(report, null, 2));
  log('saved ->', outPath);
  try { await page.send('Browser.close'); } catch {}
  try { chrome.kill(); } catch {}
  await sleep(600);
  try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch {}
  process.exit(0);
}
main().catch(async e => { console.error('ERROR:', e.message || e); try { chrome.kill(); } catch {}; await sleep(800); try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch {}; process.exit(1); });
