// Focused authenticated probe — does the APP home endpoint return a catalog with a verified session?
//
// The OTT sweep showed the WEB page never leaves verify2.php (an ad-gate shell). But the Android app
// enters at net52.cc/mobile/home?app=1 (per TokenManager). That was only ever tried UNauthenticated
// (404 / "Direct Access Not Allowed"). Now that we hold a verified t_hash_t + cf_clearance, this
// reuses that session headless and actively fetches the candidate app-home endpoints same-origin,
// dumping bodies so we can see which (if any) returns per-OTT rails.
//
// Usage: node probe-home.mjs [--session cdp-capture/session.txt] [--cookie-host net52.cc]

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
const PORT = Number(args.port || 9223);
const UA = process.env.MIRROR_BROWSER_UA || args.ua ||
  'Mozilla/5.0 (Linux; Android 10; Core-T4) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36';

const CHROME = [args.chrome, process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  (process.env.LOCALAPPDATA || '') + '/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find(p => p && fs.existsSync(p));
if (!CHROME) { console.error('Chrome not found.'); process.exit(1); }
if (!fs.existsSync(SESSION)) { console.error('No session file at', SESSION, '— earn one first (node run-discovery.mjs).'); process.exit(1); }

const cookies = {};
for (const part of fs.readFileSync(SESSION, 'utf8').split(';')) {
  const i = part.indexOf('='); if (i < 0) continue;
  cookies[part.slice(0, i).trim()] = part.slice(i + 1).trim();
}
if (!cookies.t_hash_t) { console.error('Session file has no t_hash_t.'); process.exit(1); }

const PROFILE = path.join(os.tmpdir(), 'nm-probe-' + Date.now());
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[probe]', ...a);
const chrome = spawn(CHROME, [`--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--headless=new', '--disable-gpu', 'about:blank'],
  { detached: false, stdio: 'ignore' });

class CDP {
  constructor(ws) { this.ws = new WebSocket(ws); this.id = 1; this.pending = new Map(); }
  open() { return new Promise((res, rej) => { this.ws.addEventListener('open', res); this.ws.addEventListener('error', rej);
    this.ws.addEventListener('message', ev => { const m = JSON.parse(ev.data); if (m.id && this.pending.has(m.id)) { this.pending.get(m.id)(m); this.pending.delete(m.id); } }); }); }
  send(method, params = {}) { const id = this.id++; this.ws.send(JSON.stringify({ id, method, params })); return new Promise(r => this.pending.set(id, r)); }
}

async function main() {
  let v; for (let i = 0; i < 40; i++) { try { v = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); break; } catch { await sleep(500); } }
  log('connected:', v.Browser);
  const pageWs = (await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()).find(t => t.type === 'page');
  const page = new CDP(pageWs.webSocketDebuggerUrl); await page.open();
  await page.send('Network.enable');
  await page.send('Network.setUserAgentOverride', { userAgent: UA });
  for (const [name, value] of Object.entries(cookies))
    for (const domain of [HOST, '.' + HOST])
      await page.send('Network.setCookie', { name, value, domain, path: '/', secure: true }).catch(() => {});
  const evalIn = async expr => {
    const r = await page.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
    if (r.result?.exceptionDetails) throw new Error(r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text);
    return r.result?.result?.value;
  };

  await page.send('Page.navigate', { url: `https://${HOST}/mobile/verify2.php` }); await sleep(3500);
  log('landed:', await evalIn('location.host + location.pathname'), '| CF:', await evalIn(`/just a moment/i.test(document.documentElement.outerHTML)`));

  // the token the backend derives from the session cookie
  const t = cookies.t_hash_t.split('::')[0];
  // candidate app-home / catalog endpoints, tried per OTT skin, same-origin, authenticated
  const CANDIDATES = [
    '/mobile/home?app=1', '/mobile/home.php?app=1', '/mobile/home.php', '/mobile/home',
    '/mobile/index.php?app=1', '/mobile/p.php?app=1', '/mobile/tv.php', '/mobile/movies.php',
    '/mobile/genre.php', '/mobile/filter.php', '/mobile/recentplay.php', '/mobile/MyWatchList.php',
    '/mobile/search.php?s=&t=TOKEN&ADSearch=false', '/mobile/home.php?t=TOKEN',
  ];
  const report = { host: HOST, t: '{{t}}', skins: [] };
  for (const ott of ['nf', 'pv', 'hs']) {
    await evalIn(`fetch("/mobile/setting.php",{method:"POST",headers:{"X-Requested-With":"XMLHttpRequest","Content-Type":"application/x-www-form-urlencoded; charset=UTF-8"},body:"ott=${ott}"}).then(r=>r.text()).catch(e=>String(e))`);
    const results = await evalIn(`(async()=>{
      const T=${JSON.stringify(t)};
      const H={"X-Requested-With":"XMLHttpRequest"};
      const list=${JSON.stringify(CANDIDATES)}.map(u=>u.replace("TOKEN",T));
      const red=s=>String(s).split(T).join("{{t}}");
      const out=[];
      for(const u of list){
        try{
          const r=await fetch(u,{headers:H,redirect:"manual"});
          const body=await r.text();
          let keys=null,catalog=false;
          try{ const j=JSON.parse(body); const str=JSON.stringify(j); catalog=/"(id|poster|image|img|t|title)"/i.test(str)&&str.length>80; keys=Array.isArray(j)?["<array "+j.length+">"]:Object.keys(j).slice(0,12); }catch(e){}
          out.push({u:red(u),s:r.status,type:r.type,ct:r.headers.get("content-type"),len:body.length,catalog,keys,sample:red(body.slice(0,300)).replace(/\\s+/g," ")});
        }catch(e){ out.push({u:red(u),err:String(e)}); }
      }
      return JSON.stringify(out);
    })()`);
    const parsed = JSON.parse(results);
    report.skins.push({ ott, results: parsed });
    const hits = parsed.filter(r => r.catalog);
    log(`skin ${ott}: ${hits.length} catalog-shaped of ${parsed.length} candidates`);
    for (const h of hits) log(`   FEED? ${h.s} ${h.u} (${h.len}b) keys=${JSON.stringify(h.keys)}`);
    // also surface any non-empty JSON that isn't obviously the gate, even if not flagged catalog
    for (const r of parsed.filter(x => x.keys && !x.catalog)) log(`   json  ${r.s} ${r.u} keys=${JSON.stringify(r.keys)}`);
  }

  const outPath = path.join(__dirname, 'cdp-capture', `home-probe-${new Date().toISOString().slice(0, 10)}.json`);
  fs.writeFileSync(outPath, JSON.stringify(report, null, 2));
  log('saved ->', outPath);
  try { await page.send('Browser.close'); } catch {}
  try { chrome.kill(); } catch {}
  await sleep(600);
  try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch {}
  process.exit(0);
}
main().catch(async e => { console.error('ERROR:', e.message || e); try { chrome.kill(); } catch {}; await sleep(800); try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch {}; process.exit(1); });
