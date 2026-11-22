// NetMirror per-OTT home-feed DISCOVERY — reverse-engineer the live catalog endpoint.
//
// run-capture.mjs proves the known /mobile/*.php calls and *guesses* a fixed list of home
// URLs. This script does the opposite: it switches the OTT skin (nf/pv/hs) the way the site's
// own UI does, reloads the real home page, and records (a) every /mobile/* XHR the page fires
// ON ITS OWN while rendering that skin's rails, and (b) the rendered card DOM — the correlated
// {id, poster, title} triples the server extractor needs. The page tells us its feed endpoint;
// we stop guessing.
//
// Same boundary as run-capture.mjs: a HUMAN passes the ad-gate in the visible window. This
// script only observes the gate and never fakes an impression, never touches Cloudflare, and
// writes no live cookies/tokens to the kept output.
//
// Usage: node discover-ott-feed.mjs [--port 9222] [--gate-timeout 300000] [--chrome <path>]
// Requires Node 22+ (global fetch/WebSocket; global WebSocket exists only from Node 21/22 on) and desktop Chrome. No npm installs.

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ---------- config ----------
const args = Object.fromEntries(process.argv.slice(2).reduce((a, v, i, arr) => {
  if (v.startsWith('--')) a.push([v.slice(2), arr[i + 1]]); return a;
}, []));
const PORT = Number(args.port || 9222);
const GATE_TIMEOUT_MS = Number(args['gate-timeout'] || 300000); // 5 min for the human
const OTTS = (args.otts || 'nf,pv,hs').split(',').map(s => s.trim()).filter(Boolean);

// ---------- session reuse (fully-automatic mode) ----------
// The backend never re-earns a session: MirrorAccessProperties replays a cf_clearance (+ exact UA,
// same IP, ~30 min TTL) harvested once from the device WebView. This script does the same. Feed it a
// session you ALREADY earned and it runs headless with no human: inject the cookies, skip the gate.
//  - --cookie "t_hash_t=…; cf_clearance=…"   (semicolon-separated, like a Cookie header)
//  - --cookie-file <path>                    (same string, read from a file)
//  - env MIRROR_CF_CLEARANCE / MIRROR_T_HASH_T / MIRROR_BROWSER_UA  (the backend's own vars)
// What CANNOT be automated is minting a brand-new t_hash_t from nothing — that is the one honest
// ad-view, and faking an impression is ad fraud. Reusing a valid t_hash_t is plain session replay.
const COOKIE_HOST = args['cookie-host'] || 'net52.cc';
function parseCookieString(s) {
  const out = {};
  for (const part of String(s || '').split(';')) {
    const i = part.indexOf('='); if (i < 0) continue;
    const k = part.slice(0, i).trim(), val = part.slice(i + 1).trim();
    if (k) out[k] = val;
  }
  return out;
}
const INJECT = {
  ...parseCookieString(args.cookie || (args['cookie-file'] ? fs.readFileSync(args['cookie-file'], 'utf8') : '')),
};
if (process.env.MIRROR_CF_CLEARANCE && !INJECT.cf_clearance) INJECT.cf_clearance = process.env.MIRROR_CF_CLEARANCE;
if (process.env.MIRROR_T_HASH_T && !INJECT.t_hash_t) INJECT.t_hash_t = process.env.MIRROR_T_HASH_T;
// A verified t_hash_t is what makes the run human-free; cf_clearance alone only clears Cloudflare.
const AUTO = !!INJECT.t_hash_t;
// Preflight: launch Chrome headless, attach CDP, eval, and probe backend reachability — no session,
// no gate. Proves the plumbing before the real flow needs a human.
const SELFCHECK = 'selfcheck' in args;
const HEADLESS = SELFCHECK || ('headless' in args && args.headless !== 'false');
// When set, the earned session is written here (t_hash_t + cf_clearance) so the NEXT run can reuse
// it headless. This is the one file that holds live secrets — keep it gitignored (cdp-capture/ is).
// The supervisor (run-discovery.mjs) points --cookie-file and --save-session at the same path.
const SAVE = args['save-session'] || '';
// Distinct exit code so a supervisor can tell "session expired, re-earn it" (2) from a real crash (1).
const EXIT_SESSION_EXPIRED = 2;

const LOCALAPP = process.env.LOCALAPPDATA || '';
const CHROME = [
  args.chrome, process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  LOCALAPP && `${LOCALAPP}/Google/Chrome/Application/chrome.exe`,
  // Chrome channels, then Edge (same Chromium/CDP) as a last resort so a non-standard box still runs
  'C:/Program Files/Google/Chrome Beta/Application/chrome.exe',
  'C:/Program Files/Google/Chrome SxS/Application/chrome.exe',
  LOCALAPP && `${LOCALAPP}/Google/Chrome SxS/Application/chrome.exe`,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
].find(p => p && fs.existsSync(p));
if (!CHROME) { console.error('Chrome not found. Pass --chrome <path> or set CHROME_PATH.'); process.exit(1); }

// cf_clearance is bound to the UA that minted it — when reusing a harvested session, pin that UA.
const MOBILE_UA = process.env.MIRROR_BROWSER_UA || args.ua ||
  'Mozilla/5.0 (Linux; Android 10; Core-T4) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36';
const OUT_DIR = args.out || path.join(__dirname, 'cdp-capture');
const PROFILE = path.join(os.tmpdir(), 'nm-disc-' + Date.now());
const BODY_HOSTS = [/net52\.cc$/, /net\d+\.cc$/, /iosmirror\.cc$/, /netmirror\.app$/, /mobiledetect\.app$/, /mobiledetect\.cc$/];
fs.mkdirSync(OUT_DIR, { recursive: true });

const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[disc]', ...a);

// ---------- launch chrome ----------
const chrome = spawn(CHROME, [
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check',
  ...(HEADLESS ? ['--headless=new', '--disable-gpu'] : []),
  '--new-window', 'about:blank',
], { detached: false, stdio: 'ignore' });

async function waitForDevtools() {
  for (let i = 0; i < 40; i++) {
    try { const v = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); return v; }
    catch { await sleep(500); }
  }
  throw new Error('DevTools endpoint did not come up');
}

// ---------- minimal CDP client ----------
class CDP {
  constructor(wsUrl) { this.ws = new WebSocket(wsUrl); this.id = 1; this.pending = new Map(); this.handlers = []; }
  open() { return new Promise((res, rej) => {
    this.ws.addEventListener('open', res); this.ws.addEventListener('error', rej);
    this.ws.addEventListener('message', ev => {
      const m = JSON.parse(ev.data);
      if (m.id && this.pending.has(m.id)) { this.pending.get(m.id)(m); this.pending.delete(m.id); }
      else for (const h of this.handlers) h(m);
    });
  }); }
  send(method, params = {}, sessionId) {
    const id = this.id++; const msg = { id, method, params }; if (sessionId) msg.sessionId = sessionId;
    this.ws.send(JSON.stringify(msg));
    return new Promise(res => this.pending.set(id, res));
  }
  on(fn) { this.handlers.push(fn); }
}

// ---------- capture state ----------
// records[]: time-ordered {kind:'req'|'resp'|'body', ...}. We slice it per OTT by index so each
// skin's home load is attributed to exactly the XHRs it fired.
const records = [];
const reqUrl = new Map();
const hostOf = u => { try { return new URL(u).host; } catch { return ''; } };
const pathOf = u => { try { const x = new URL(u); return x.host + x.pathname; } catch { return u; } };
const wantBody = u => BODY_HOSTS.some(r => r.test(hostOf(u)));
let pageSession = null;

async function main() {
  const v = await waitForDevtools();
  log('connected:', v.Browser);
  const cdp = new CDP(v.webSocketDebuggerUrl);
  await cdp.open();

  await cdp.send('Target.setDiscoverTargets', { discover: true });
  await cdp.send('Target.setAutoAttach', { autoAttach: true, waitForDebuggerOnStart: true, flatten: true });

  cdp.on(async m => {
    const sess = m.sessionId;
    switch (m.method) {
      case 'Target.attachedToTarget': {
        const sid = m.params.sessionId;
        if (m.params.targetInfo.type === 'page' && !pageSession) pageSession = sid;
        await cdp.send('Network.enable', {}, sid).catch(() => {});
        await cdp.send('Network.setUserAgentOverride', {
          userAgent: MOBILE_UA, acceptLanguage: 'en-US,en;q=0.9', platform: 'Android',
          userAgentMetadata: { platform: 'Android', platformVersion: '10', model: 'Core-T4', mobile: true,
            brands: [{ brand: 'Chromium', version: '120' }], fullVersion: '120.0.0.0' },
        }, sid).catch(() => {});
        await cdp.send('Page.enable', {}, sid).catch(() => {});
        await cdp.send('Runtime.runIfWaitingForDebugger', {}, sid).catch(() => {});
        break;
      }
      case 'Network.requestWillBeSent':
        reqUrl.set(`${sess}:${m.params.requestId}`, m.params.request.url);
        records.push({ kind: 'req', method: m.params.request.method, url: m.params.request.url,
          type: m.params.type, hasPostData: !!m.params.request.postData });
        break;
      case 'Network.responseReceived':
        records.push({ kind: 'resp', status: m.params.response.status, url: m.params.response.url, mime: m.params.response.mimeType });
        break;
      case 'Network.loadingFinished': {
        const url = reqUrl.get(`${sess}:${m.params.requestId}`) || '';
        if (url && wantBody(url)) {
          const r = await cdp.send('Network.getResponseBody', { requestId: m.params.requestId }, sess).catch(() => null);
          if (r?.result) records.push({ kind: 'body', url, body: r.result.base64Encoded ? Buffer.from(r.result.body, 'base64').toString('utf8') : r.result.body });
        }
        break;
      }
    }
  });

  await sleep(800);
  const pageWs = (await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()).find(t => t.type === 'page');
  const page = new CDP(pageWs.webSocketDebuggerUrl); await page.open();
  const nav = url => page.send('Page.navigate', { url });
  const evalIn = async expr => {
    const r = await page.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
    if (r.error) throw new Error('evaluate failed: ' + r.error.message);
    if (r.result?.exceptionDetails) throw new Error('page exception: ' + (r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text));
    return r.result?.result?.value;
  };

  // Persist the live session (incl. HttpOnly cf_clearance, which document.cookie cannot see) so the
  // next run reuses it. Uses CDP's cookie jar, not document.cookie, precisely to catch HttpOnly.
  const saveSession = async () => {
    if (!SAVE) return;
    const jar = (await page.send('Network.getCookies', { urls: [`https://${COOKIE_HOST}/`] }).catch(() => null))?.result?.cookies || [];
    // Persist the WHOLE first-party net52.cc jar, not a hand-picked few. /mobile/home?app=1 is
    // cookie-gated: with only t_hash_t (+cf_clearance+addhash) it answers "Page Not Found!" (404) and
    // nf/dp rails come back empty, because the home-session cookies (t_hash, and the numeric skin
    // cookies the live app carries) are dropped. The backend replays this line verbatim as the Cookie
    // header and MirrorOtt.apply strips+re-sets `ott` per request, so carrying the full jar — `ott`
    // included — is safe and loses nothing. Values never reach the log; only names are printed.
    const vals = new Map();                               // name -> value, deduped (CDP jar has per-domain dupes)
    for (const c of jar) { if (c && c.name && c.value != null) vals.set(c.name, c.value); }
    // Fall back to injected cookies (incl. HttpOnly cf_clearance) for any name a reused/headless run
    // did not re-observe in the jar; the jar value wins when both exist.
    for (const [n, v] of Object.entries(INJECT)) { if (v && !vals.has(n)) vals.set(n, v); }
    // A Cookie header is `name=value; name=value`; a name/value carrying a delimiter (`; , =` or CR/LF)
    // would corrupt the single line (readSessionFile also rejects multiline), so drop that pair rather
    // than emit a malformed header. `=` is legal inside a value (token padding), only illegal in a name.
    const pairs = [];
    for (const [n, v] of vals) {
      if (/[;,=\s]/.test(n) || /[;,\r\n]/.test(String(v))) continue;
      pairs.push(`${n}=${v}`);
    }
    // t_hash_t is the marker the backend requires (MirrorSessionProvider.readSessionFile); never write a
    // line without it — a partial jar missing it would be rejected or replay a dead session.
    if (!pairs.some(p => p.startsWith('t_hash_t='))) { log('  (not saving session — no t_hash_t in jar)'); return; }
    fs.writeFileSync(SAVE, pairs.join('; ') + '\n', { mode: 0o600 });
    log(`  saved live session -> ${SAVE} (${pairs.length} cookies: ${pairs.map(p => p.slice(0, p.indexOf('='))).join(', ')}; holds secrets, gitignored)`);
  };

  // ---- preflight: prove Chrome + CDP + reachability, then stop (no session, no gate) ----
  if (SELFCHECK) {
    log('selfcheck — Chrome:', path.basename(CHROME), HEADLESS ? '[headless]' : '');
    const two = await evalIn('1 + 1');
    log('selfcheck — CDP Runtime.evaluate:', two === 2 ? 'OK (1+1=2)' : 'UNEXPECTED ' + two);
    // navigate TO the backend origin (as the real flow does) so this is a true reachability test,
    // not a CORS-blocked cross-origin fetch
    await nav(`https://${COOKIE_HOST}/mobile/verify2.php`); await sleep(3500);
    const page1 = await evalIn(`JSON.stringify({ready:document.readyState, url:location.host+location.pathname, title:document.title.slice(0,60), len:document.documentElement.outerHTML.length})`);
    log(`selfcheck — reached ${COOKIE_HOST}:`, page1);
    const cf = await evalIn(`/just a moment|checking your browser|cf-browser-verification/i.test(document.documentElement.outerHTML)`);
    log('selfcheck — Cloudflare challenge:', cf ? 'PRESENT (real flow needs a cf_clearance / live browser pass)' : 'not blocking right now');
    log('selfcheck — plumbing OK. Real flow: node run-discovery.mjs --watch 1500');
    return await cleanup(cdp, 0);
  }

  // ---- gate: AUTO (reuse an earned session) or HUMAN (earn a new one) ----
  if (AUTO) {
    // Inject the already-earned cookies and let real Chrome clear Cloudflare with them. No window,
    // no ad-view — this is the same session replay the backend does, run from the same IP.
    log(`step 1 — AUTO mode: injecting earned session (${Object.keys(INJECT).join(', ')})${HEADLESS ? ' [headless]' : ''}`);
    await page.send('Network.enable').catch(() => {});
    for (const [name, value] of Object.entries(INJECT)) {
      if (!value) continue;
      for (const domain of [COOKIE_HOST, '.' + COOKIE_HOST]) {
        await page.send('Network.setCookie', { name, value, domain, path: '/', secure: true }).catch(() => {});
      }
    }
    await nav(`https://${COOKIE_HOST}/mobile/verify2.php`); await sleep(4000);
    // (a) did the session survive Cloudflare at all? No t_hash_t cookie => dead/expired, or the UA/IP
    // the cf_clearance was bound to was rejected.
    const okt = await evalIn(`document.cookie.includes("t_hash_t")`);
    if (!okt) {
      console.log('\nInjected t_hash_t did not survive — it is likely expired, or Cloudflare rejected the');
      console.log('UA/IP it was bound to. Re-harvest a fresh session (one ad-view) and pass it again,');
      console.log('or drop --cookie to fall back to the human gate.');
      return await cleanup(cdp, EXIT_SESSION_EXPIRED);
    }
    // (b) run the SAME gate verify the backend runs (AdGateRepository.verifyAds): POST verify=<addhash>
    // to verify2.php and require the cleared marker. The old check (t_hash_t present) passed sessions the
    // backend then 412'd — clearance needs the addhash ad-click proof, not just t_hash_t. Matching the
    // consumer here means AUTO "success" implies the backend will accept it; otherwise exit "expired" so
    // the supervisor re-earns with one honest ad-view instead of looping on a session the backend rejects.
    const gate = await evalIn(`(async()=>{
      try{
        const m = document.cookie.match(/addhash=([^;]+)/);
        const addhash = m ? decodeURIComponent(m[1]) : "";
        const r = await fetch("/mobile/verify2.php", {
          method: "POST",
          headers: {"Content-Type":"application/x-www-form-urlencoded; charset=UTF-8","X-Requested-With":"XMLHttpRequest"},
          body: "verify=" + encodeURIComponent(addhash)
        });
        return JSON.stringify({status:r.status, cleared:/"statusup":"OK"|"c":"y"/.test(await r.text())});
      }catch(e){ return JSON.stringify({err:String(e)}); }
    })()`);
    // gate holds only {status, cleared(bool)} — never the response body, so no token reaches the log.
    if (!/"cleared":true/.test(gate)) {
      console.log('\nSession survived Cloudflare but the ad-gate is NOT cleared for it (verify2 did not return');
      console.log('statusup:OK). The addhash ad-click proof is missing or spent — re-earn with one ad-view.');
      return await cleanup(cdp, EXIT_SESSION_EXPIRED);
    }
    log('step 3 — gate verified (verify2 returned cleared, same check the backend runs), no human step');
  } else {
    log('step 1 — dispatcher check.php');
    await nav('https://mobiledetect.app/check.php'); await sleep(2500);
    log('step 2 — verify2 shell (seeds the not-verified cookie)');
    // a harvested cf_clearance (if supplied) still helps real Chrome past Cloudflare faster
    if (INJECT.cf_clearance) {
      await page.send('Network.enable').catch(() => {});
      for (const domain of [COOKIE_HOST, '.' + COOKIE_HOST]) {
        await page.send('Network.setCookie', { name: 'cf_clearance', value: INJECT.cf_clearance, domain, path: '/', secure: true }).catch(() => {});
      }
    }
    await nav(`https://${COOKIE_HOST}/mobile/verify2.php`); await sleep(3500);

    console.log('\n==================================================================');
    console.log('  ACTION NEEDED (human): in the Chrome window now open —');
    console.log('   1. click "Click Here" (Open 1 ADS per Day)');
    console.log('   2. watch the ad for MORE THAN 20 seconds');
    console.log('   3. close the ad tab and return to the NetMirror tab');
    console.log('  The script is only WATCHING the gate; it will NOT flip it for you.');
    console.log('  (Tip: pass --cookie "t_hash_t=…; cf_clearance=…" to skip this entirely next time.)');
    console.log('==================================================================\n');
    const deadline = Date.now() + GATE_TIMEOUT_MS;
    let verified = false;
    while (Date.now() < deadline) {
      const has = await evalIn(`document.cookie.includes("t_hash_t")`);
      if (has) { verified = true; break; }
      process.stdout.write('  waiting for honest ad-view… \r');
      await sleep(3000);
    }
    if (!verified) { console.log('\nGate not passed within timeout — stopping.'); return await cleanup(cdp, EXIT_SESSION_EXPIRED); }
    log('\nstep 3 — gate verified (t_hash_t cookie present)');
  }
  // the session is now live (reused or freshly earned) — persist it for the next automatic run.
  // NB: snapshotting the jar is all we do here. An earlier revision warmed /mobile/home?app=1 first,
  // on the theory the gate did not mint the home-session cookies (t_hash + numeric skin cookies) and
  // home would. That was EMPIRICALLY DISPROVEN (2026-11-22): the desktop gate flow only ever yields
  // t_hash_t + addhash, home 404s precisely because the 8-cookie app-native jar is absent, and warming
  // it minted nothing. The full-jar saveSession (above) stays; the dead home-warm was reverted.
  await saveSession();
  await sleep(4000);
  const landed = await evalIn('location.origin + location.pathname');
  log('  verify2 landed on', landed);

  // the original skin, so the sweep can restore it
  const originalOtt = await evalIn(`(async()=>{ try{ const r=await fetch("/mobile/setting.php",{headers:{"X-Requested-With":"XMLHttpRequest"}}); const j=JSON.parse(await r.text()); return j.ott||""; }catch(e){ return ""; } })()`);
  log('  current skin reads as', originalOtt || '(unknown)');

  // ---- redaction: this output is kept, so strip the session token wherever it appears ----
  // The catalog data itself (ids, titles, poster URLs) is the whole point and is NOT secret;
  // only the cookie-derived token and any 20+ char opaque blob get masked.
  const cookieT = await evalIn(`(()=>{ const m=document.cookie.match(/t_hash_t=([^;]+)/); return m?decodeURIComponent(m[1]).split("::")[0]:""; })()`);
  const redact = s => {
    if (s == null) return s;
    let o = String(s).replace(/([?&]t=)[^&"'\s]+/g, '$1{{t}}');
    if (cookieT && cookieT.length >= 6) o = o.split(cookieT).join('{{t}}');
    return o;
  };

  // ---- per-OTT sweep ----
  const H = '{"X-Requested-With":"XMLHttpRequest"}';
  const sweep = [];
  for (const ott of OTTS) {
    log(`step 4 — skin ${ott}: switch, reload home, observe`);
    // switch the skin the way the site's own settings UI does
    const switchResp = await evalIn(`(async()=>{
      try{
        const r=await fetch("/mobile/setting.php",{method:"POST",headers:{...${H},"Content-Type":"application/x-www-form-urlencoded; charset=UTF-8"},body:"ott=${ott}"});
        return JSON.stringify({s:r.status,body:(await r.text()).slice(0,200)});
      }catch(e){ return JSON.stringify({err:String(e)}); }
    })()`);

    const startIdx = records.length;      // everything after this belongs to this skin's home load
    await nav(landed); await sleep(5500);  // let the home page fire its own feed XHRs and render

    // (a) the XHRs the page fired on its own while rendering this skin
    const slice = records.slice(startIdx);
    const reqs = slice.filter(r => r.kind === 'req' && wantBody(r.url));
    const resps = new Map(slice.filter(r => r.kind === 'resp').map(r => [pathOf(r.url), r]));
    const bodies = new Map(slice.filter(r => r.kind === 'body').map(r => [pathOf(r.url), r.body]));
    const firedEndpoints = [...new Map(reqs.map(r => {
      const key = r.method + ' ' + pathOf(r.url);
      const resp = resps.get(pathOf(r.url));
      const body = bodies.get(pathOf(r.url)) || '';
      // is this a catalog feed? JSON that carries ids + titles is the tell
      let shape = null;
      if (body) {
        try {
          const j = JSON.parse(body);
          const str = JSON.stringify(j);
          shape = {
            looksLikeCatalog: /"(id|t|title|poster|image|img)"/i.test(str),
            topKeys: Array.isArray(j) ? ['<array len ' + j.length + '>'] : Object.keys(j).slice(0, 12),
            sample: redact(str.slice(0, 600)),
          };
        } catch { shape = { json: false, sample: redact(body.slice(0, 200)) }; }
      }
      return [key, { method: r.method, path: pathOf(r.url), type: r.type,
        status: resp?.status, mime: resp?.mime, bodyLen: body.length, shape }];
    })).values()];

    // (b) the rendered card DOM — correlated {id, poster, title} triples, and the selector shape
    const dom = await evalIn(`(()=>{
      const out={cards:[],containerSamples:[],imgHosts:{}};
      // start from every poster-ish image, walk up to the nearest id-bearing/anchor container
      const imgs=[...document.querySelectorAll('img')];
      for(const img of imgs){
        const src=img.currentSrc||img.src||img.getAttribute('data-src')||'';
        if(!src) continue;
        try{ const h=new URL(src,location.href).host; out.imgHosts[h]=(out.imgHosts[h]||0)+1; }catch(e){}
        let el=img, id='', href='';
        for(let i=0;i<6&&el;i++){
          const cand=el.getAttribute&&(el.getAttribute('data-id')||el.getAttribute('data-post')||(/^\\d{5,}$/.test(el.id||'')?el.id:''));
          if(cand&&!id) id=cand;
          if(el.tagName==='A'&&el.getAttribute('href')&&!href) href=el.getAttribute('href');
          el=el.parentElement;
        }
        const idFromHref=(href.match(/(?:id=|\\/)(\\d{5,})/)||[])[1]||'';
        const finalId=id||idFromHref;
        if(finalId && out.cards.length<40){
          out.cards.push({id:finalId, poster:src.replace(/\\?.*$/,''), title:(img.alt||img.title||'').trim().slice(0,80), href});
        }
      }
      // what container selector these cards share, for the server extractor
      const seen=new Set();
      for(const c of out.cards.slice(0,8)){
        const node=[...document.querySelectorAll('img')].find(i=>(i.currentSrc||i.src||'').includes(c.poster.split('/').pop()));
        let p=node; const chain=[];
        for(let i=0;i<4&&p;i++){ chain.push(p.tagName.toLowerCase()+(p.className&&typeof p.className==='string'?'.'+p.className.trim().split(/\\s+/).slice(0,2).join('.'):'')); p=p.parentElement; }
        const sig=chain.join(' < '); if(!seen.has(sig)){ seen.add(sig); out.containerSamples.push(sig); }
      }
      out.cardCount=out.cards.length;
      out.rowHeadings=[...document.querySelectorAll('h1,h2,h3,[class*=title i],[class*=head i]')].map(e=>e.textContent.trim()).filter(t=>t&&t.length<60).slice(0,20);
      return JSON.stringify(out);
    })()`);

    sweep.push({ ott, switchResp: JSON.parse(switchResp), landed: redact(landed),
      firedEndpoints, dom: JSON.parse(dom || '{}') });

    const catalogHits = firedEndpoints.filter(e => e.shape?.looksLikeCatalog);
    log(`  skin ${ott}: ${firedEndpoints.length} backend XHRs, ${catalogHits.length} catalog-shaped, ${JSON.parse(dom||'{}').cardCount||0} rendered cards`);
    for (const e of catalogHits) log(`    FEED? ${String(e.status).padEnd(3)} ${e.method} ${e.path} (${e.bodyLen}b)`);
  }

  // restore the skin the account started on
  if (originalOtt && OTTS.includes(originalOtt) && originalOtt !== OTTS[OTTS.length - 1]) {
    await evalIn(`fetch("/mobile/setting.php",{method:"POST",headers:{...${H},"Content-Type":"application/x-www-form-urlencoded; charset=UTF-8"},body:"ott=${originalOtt}"}).catch(()=>{})`);
    log('  restored skin to', originalOtt);
  }

  // every same-site path the browser touched across the whole run — the endpoint surface
  const allPaths = [...new Map(records.filter(r => r.kind === 'resp' && wantBody(r.url))
    .map(r => { const u = new URL(r.url); return [u.host + u.pathname, { status: r.status, path: u.host + u.pathname, mime: r.mime }]; })).values()];

  const outPath = path.join(OUT_DIR, `ott-feed-discovery-${new Date().toISOString().slice(0, 10)}.json`);
  fs.writeFileSync(outPath, JSON.stringify({
    captured: new Date().toISOString(),
    note: 'Per-OTT home-feed discovery. Session token redacted to {{t}}. Catalog ids/titles/posters kept — they are the target data, not secrets.',
    landed: redact(landed), otts: OTTS, originalOtt, sweep, allBackendPaths: allPaths,
  }, null, 2));
  log('step 5 — saved discovery ->', outPath);
  console.log('\nNext: read that file. The feed endpoint is the catalog-shaped XHR each skin fired,');
  console.log('and dom.cards shows the {id, poster, title} shape the server extractor must produce.');

  await cleanup(cdp);
}

async function cleanup(cdp, code = 0) {
  try { await cdp.send('Browser.close'); } catch {}
  await sleep(1000);
  try { chrome.kill(); } catch {}
  await sleep(800);
  try { fs.rmSync(PROFILE, { recursive: true, force: true }); log('purged throwaway profile'); } catch {}
  log(code === EXIT_SESSION_EXPIRED ? 'done (session expired — exit 2).' : 'done.');
  process.exit(code);
}

main().catch(async e => {
  console.error('ERROR:', e.message || e);
  try { chrome.kill(); } catch {}
  await sleep(1500);
  try { fs.rmSync(PROFILE, { recursive: true, force: true }); log('purged throwaway profile'); } catch {}
  process.exit(1);
});
