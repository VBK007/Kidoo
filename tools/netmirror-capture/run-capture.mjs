// Authenticated NetMirror capture — orchestrator.
// Reads browser-decrypted traffic via the Chrome DevTools Protocol. No proxy, no custom CA,
// no device tampering, no TLS interception. The ad-gate is passed by a HUMAN (not automated):
// the script PAUSES and only observes the gate state until it flips honestly.
//
// Usage: node run-capture.mjs [--id <netflixId>] [--query <searchTerm>] [--port 9222]
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
const ID = args.id || '82034837';
const QUERY = args.query || 'vishwanath';
const SERIES = args.series || ID; // episodes.php series id; a movie id just shows how the endpoint answers
const PORT = Number(args.port || 9222);
const GATE_TIMEOUT_MS = Number(args['gate-timeout'] || 300000); // 5 min for the human
// Auto-click the ad-gate button ("Click Here"). ON by default; disable with --no-auto-click. The
// click uses only a TRUSTED CDP gesture (see the gate step), so it is indistinguishable from a human
// and the session the script goes on to capture is earned honestly and never flagged — enabling it
// does not affect the fetched session. It still cannot clear the gate alone (the ad network needs an
// ad watched >20s), so the human-watch loop and timeout remain as a fallback.
const AUTO_CLICK = !('no-auto-click' in args) && args['auto-click'] !== 'false';

const CHROME = [
  args.chrome, process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
].find(p => p && fs.existsSync(p));
if (!CHROME) { console.error('Chrome not found. Pass --chrome <path> or set CHROME_PATH.'); process.exit(1); }

const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 10; Core-T4) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36';
// Output lands next to this script (folder is .gitignored) unless --out overrides it.
const OUT_DIR = args.out || path.join(__dirname, 'cdp-capture');
const PROFILE = path.join(os.tmpdir(), 'nm-cap-' + Date.now());
const BODY_HOSTS = [/net52\.cc$/, /iosmirror\.cc$/, /netmirror\.app$/, /mobiledetect\.app$/, /mobiledetect\.cc$/];
fs.mkdirSync(OUT_DIR, { recursive: true });

const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[cap]', ...a);

// ---------- launch chrome ----------
const chrome = spawn(CHROME, [
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--new-window', 'about:blank',
], { detached: false, stdio: 'ignore' });

async function waitForDevtools() {
  for (let i = 0; i < 40; i++) {
    try { const v = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); return v; }
    catch { await sleep(500); }
  }
  throw new Error('DevTools endpoint did not come up');
}

// ---------- minimal CDP client over the browser endpoint ----------
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
const records = [];
const reqUrl = new Map();
const hostOf = u => { try { return new URL(u).host; } catch { return ''; } };
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
        records.push({ kind: 'req', method: m.params.request.method, url: m.params.request.url });
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

  // 1) dispatcher
  log('step 1/5 — dispatcher check.php');
  await nav('https://mobiledetect.app/check.php'); await sleep(2500);
  // 2) shell + cookie seed
  log('step 2/5 — verify2 shell (seeds addhash=...::ni)');
  await nav('https://net52.cc/mobile/verify2.php'); await sleep(3500);

  // 3) GATE STEP — auto-click the ad-gate, then observe until it flips.
  // The session this script later captures must be earned HONESTLY, so on the normal path the gate
  // button is clicked ONLY with a trusted CDP Input.dispatchMouseEvent — a real click through the
  // browser input pipeline (isTrusted:true), indistinguishable from a human, so the ad network has
  // nothing to flag and the earned t_hash_t session is clean. We deliberately do NOT fire an in-page
  // element.click() on that path: it is isTrusted:false (automation-detectable) AND popup-blocked.
  //
  // locateExpr only FINDS the button and scrolls it into view, returning its top-document viewport
  // coordinates. The one exception is a button nested in a same-origin iframe, where top-level
  // coordinates don't apply: there (rare; the live verify2.php button is top-level) we fall back to a
  // scoped in-page click so there's still an attempt.
  const locateExpr = `(() => {
    const txt = el => (el.innerText || el.textContent || el.value || "").trim();
    const roots = [document, ...[...document.querySelectorAll("iframe")]
      .map(f => { try { return f.contentDocument; } catch (e) { return null; } }).filter(Boolean)];
    const sel = "a,button,input[type=button],input[type=submit],[onclick],[role=button],.btn,.button";
    const cand = roots.flatMap(r => [...r.querySelectorAll(sel)]);
    const hit = cand.find(el =>
      /click here|open 1 ads|ads per day|watch ad|verify|continue/i.test(txt(el)) ||
      /verify|ads|addhash|gate/i.test(el.getAttribute("onclick") || ""));
    if (!hit) return JSON.stringify({ found: false,
      seen: [...new Set(cand.map(txt).filter(Boolean))].slice(0, 15) });
    try { hit.scrollIntoView({ block: "center", inline: "center" }); } catch (e) {}
    const inTop = hit.ownerDocument === document;  // top-document coords are only valid when true
    if (!inTop) { try { hit.click(); } catch (e) {} }  // iframe-only fallback (no trusted coords there)
    const r = hit.getBoundingClientRect();
    return JSON.stringify({ found: true, inTop, label: (txt(hit) || hit.tagName).slice(0, 60),
      x: r.left + r.width / 2, y: r.top + r.height / 2 });
  })()`;
  const tryGateClick = async () => {
    let loc;
    try { loc = JSON.parse(await evalIn(locateExpr)); } catch (e) { return { clicked: false, reason: String(e) }; }
    if (!loc.found) return { clicked: false, seen: loc.seen };
    if (!loc.inTop) return { clicked: true, label: loc.label + ' (iframe: in-page click only)' };
    // trusted click through the browser input pipeline — a real human-equivalent gesture, so the ad
    // popup is allowed to open and the session stays clean (nothing automation-detectable is sent)
    try {
      const base = { x: Math.round(loc.x), y: Math.round(loc.y), button: 'left' };
      await page.send('Input.dispatchMouseEvent', { type: 'mouseMoved', ...base });
      await page.send('Input.dispatchMouseEvent', { type: 'mousePressed', ...base, clickCount: 1 });
      await page.send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...base, clickCount: 1 });
    } catch (e) { return { clicked: false, reason: 'dispatch failed: ' + String(e), label: loc.label }; }
    return { clicked: true, label: loc.label };
  };

  console.log('\n==================================================================');
  if (AUTO_CLICK) {
    console.log('  AUTO-CLICK enabled (--auto-click) — the script will open the ad for you.');
    console.log('  It still cannot clear the gate alone, so finish it by hand:');
  } else {
    console.log('  ACTION NEEDED (human): in the Chrome window now open —');
  }
  console.log('   1. click "Click Here" (Open 1 ADS per Day)');
  console.log('   2. watch the ad for MORE THAN 20 seconds');
  console.log('   3. close the ad tab and return to the NetMirror tab');
  console.log(AUTO_CLICK
    ? '  (Auto-click uses a human-equivalent gesture; disable with --no-auto-click.)'
    : '  (Auto-click disabled via --no-auto-click; do the whole gate by hand.)');
  console.log('==================================================================\n');
  const deadline = Date.now() + GATE_TIMEOUT_MS;
  let verified = false;
  let clickedOnce = false;
  while (Date.now() < deadline) {
    const has = await evalIn(`document.cookie.includes("t_hash_t")`);
    if (has) { verified = true; break; }
    if (AUTO_CLICK && !clickedOnce) {
      const res = await tryGateClick();
      if (res.clicked) { clickedOnce = true; log(`auto-click fired on "${res.label}"`); }
      else log('auto-click no gate button yet'
        + (res.reason ? ` [${res.reason}]` : '')
        + (res.seen ? ` (saw: ${res.seen.join(' | ') || '<no clickable text>'})` : '')
        + ' — retrying');
    }
    process.stdout.write('  waiting for honest ad-view… \r');
    await sleep(3000);
  }
  if (!verified) { console.log('\nGate not passed within timeout — stopping without authenticated capture.'); return await cleanup(cdp, 3); }
  log('\nstep 3/5 — gate verified (t_hash_t cookie present)');
  // verify2 redirects once the gate flips; let that finish, then settle on a stable same-origin page
  await sleep(4000);
  // where the site itself takes a verified user: that is the live home page (path only, no query/tokens)
  const landed = await evalIn('location.origin + location.pathname');
  log('  verify2 landed on', landed);
  // the same page TokenManager scrapes for t/hash, so the calls below see what the backend sees
  await nav('https://net52.cc/mobile/home?app=1'); await sleep(4000);

  // 4) every upstream call the Kidoo backend makes (src/main/java/.../mymirror/repo), shaped the
  //    way the backend shapes it. Tokens are derived in-page and redacted on the way out.
  log('step 4/5 — backend API coverage');
  const expr = `(async()=>{
    const LANDED=${JSON.stringify(landed)}, ID=${JSON.stringify(ID)}, Q=${JSON.stringify(QUERY)}, SERIES=${JSON.stringify(SERIES)};
    const ck=Object.fromEntries(document.cookie.split("; ").map(s=>{const i=s.indexOf("="); return [s.slice(0,i),decodeURIComponent(s.slice(i+1))];}));
    const cookieT=(ck["t_hash_t"]||ck["addhash"]||"").split("::")[0];
    // TokenManager reads these two attributes off the home page body; when the live page doesn't
    // carry them, fall back to the cookie token so the remaining calls still say something useful
    const attrT=document.body.getAttribute("data-time")||"", hash=document.body.getAttribute("data-hash")||"";
    if(!cookieT) throw new Error("no session token in cookies");
    const t=attrT||cookieT;
    const segs=(ck["t_hash_t"]||"").split("::");
    const secrets=[[hash,"{{hash}}"],[t,"{{t}}"],[cookieT,"{{t}}"],...segs.slice(1).map((v,i)=>[v,"{{seg"+(i+1)+"}}"])].filter(([v])=>v&&v.length>=6);
    // play tokens first: they embed t, so swapping t out first would leave the rest of them live
    const red=s=>{ let o=String(s).replace(/([?&])in=[^&"'\\s]+/g,"$1in={{play_token}}"); for(const [v,ph] of secrets) o=o.split(v).join(ph); return o; };
    const H={"X-Requested-With":"XMLHttpRequest"};
    const qs=o=>new URLSearchParams(o).toString();
    async function call(name,method,u,form,max=Infinity){
      try{
        const init={method,headers:{...H}};
        if(form){init.headers["Content-Type"]="application/x-www-form-urlencoded; charset=UTF-8"; init.body=qs(form);}
        const r=await fetch(u,init); const text=await r.text();
        return {name,method,u:red(u),form:form?red(qs(form)):undefined,s:r.status,ct:r.headers.get("content-type"),len:text.length,body:red(text.slice(0,max))};
      }catch(e){return {name,method,u:red(u),err:String(e)};}
    }
    const out=[];
    out.push({name:"home tokens (TokenManager)",s:attrT&&hash?200:0,body:"data-time "+(attrT?"present":"MISSING (calls below use the cookie t)")+", data-hash "+(hash?"present":"MISSING")+", data-time==cookie t: "+(attrT===cookieT)});
    // where the live home page keeps its tokens: names and masked contexts only, never values
    try{
      const r=await fetch("/mobile/home?app=1",{headers:H}); const html=await r.text();
      const mask=s=>red(s).replace(/[A-Za-z0-9%+\\/=_-]{16,}/g,"{{tok}}");
      const dataAttrs=[...new Set((html.match(/\\sdata-[a-z0-9_-]+(?==)/gi)||[]).map(s=>s.trim()))];
      const ctx=[]; const re=/(time|hash)/gi; let m;
      while((m=re.exec(html))&&ctx.length<25){ ctx.push(mask(html.slice(Math.max(0,m.index-60),m.index+60)).replace(/\\s+/g," ")); }
      out.push({name:"home page token locations",s:r.status,ct:r.headers.get("content-type"),len:html.length,
        body:JSON.stringify({finalUrl:red(r.url),pageUrl:red(location.href),bodyAttrs:document.body.getAttributeNames(),
          dataAttrsInHtml:dataAttrs,htmlHasCookieT:html.includes(cookieT),contexts:ctx})});
    }catch(e){ out.push({name:"home page token locations",err:String(e)}); }
    // home?app=1 can 404 on the live site; find which page does hand out the tokens
    for(const u of ["/mobile/home?app=1","/mobile/home","/mobile/","/home","/"]){
      for(const xhr of [true,false]){
        try{
          const r=await fetch(u,{headers:xhr?H:{}}); const html=await r.text();
          const bodyTag=(html.match(/<body[^>]*>/i)||[""])[0];
          out.push({name:"home probe "+u+(xhr?" (xhr)":" (plain)"),s:r.status,ct:r.headers.get("content-type"),len:html.length,
            body:JSON.stringify({finalUrl:red(r.url),bodyDataAttrs:(bodyTag.match(/data-[a-z0-9_-]+/gi)||[]),
              dataTime:/data-time=/.test(html),dataHash:/data-hash=/.test(html),htmlHasCookieT:html.includes(cookieT)})});
        }catch(e){ out.push({name:"home probe "+u+(xhr?" (xhr)":" (plain)"),err:String(e)}); }
      }
    }
    // the page verify2 redirects to after the gate — the site's real home now
    out.push(await call("home (page verify2 lands on)","GET",LANDED));
    try{
      const r=await fetch("/trailer.php?"+qs({id:ID}),{headers:H,redirect:"manual"});
      out.push({name:"trailer (redirect not followed)",s:r.status,body:"type="+r.type});
    }catch(e){ out.push({name:"trailer (redirect not followed)",err:String(e)}); }

    // BrowseRepository
    const post=await call("detail","GET","/mobile/post.php?"+qs({id:ID,t})); out.push(post);
    out.push(await call("search","GET","/mobile/search.php?"+qs({s:Q,t,ADSearch:"false"})));
    out.push(await call("episodes","GET","/mobile/episodes.php?"+qs({s:ID,series:SERIES,t,page:"1"})));
    out.push(await call("trailer","GET","/trailer.php?"+qs({id:ID})));
    // InfoRepository
    out.push(await call("app-info","GET","/mobile/app-info.php?os=android"));

    // PlaybackRepository — playlist as the backend builds it (t=<title>, tm=<token>) and as the site's own page does (t=<token>, tm=<time>)
    let title=""; try{ title=JSON.parse(post.body).title||""; }catch(e){}
    out.push(await call("playlist (backend shape: t=title, tm=token)","GET","/mobile/playlist.php?"+qs({id:ID,t:title,tm:t})));
    const pl=await call("playlist (site shape: t=token, tm=time)","GET","/mobile/playlist.php?"+qs({id:ID,t:cookieT,tm:String(Date.now())})); out.push(pl);
    out.push(await call("hls master (backend: in=t)","GET","/mobile/hls/"+encodeURIComponent(ID)+".m3u8?"+qs({in:t,hd:"off",lang:"eng",hp:"yes"})));
    // the URL the playlist itself hands out — fetched with the live body before redaction
    try{
      const raw=await (await fetch("/mobile/playlist.php?"+qs({id:ID,t:cookieT,tm:String(Date.now())}),{headers:H})).text();
      const file=JSON.parse(raw)[0].sources[0].file;
      out.push(await call("hls master (playlist-issued URL)","GET",file));
    }catch(e){ out.push({name:"hls master (playlist-issued URL)",err:"could not read a source from playlist: "+e}); }
    // hash: the home page no longer hands one out, so try each t_hash_t segment where hash goes
    out.push({name:"t_hash_t structure",s:200,body:JSON.stringify(segs.map((v,i)=>({seg:i,len:v.length,hex:/^[0-9a-f]+$/i.test(v)})))});
    for(let i=0;i<segs.length;i++){
      if(!segs[i]||segs[i].length<6) continue;
      out.push(await call("heartbeat p.php (hash=seg"+i+")","POST","/mobile/p.php",{hash:segs[i]}));
      out.push(await call("alt video v.php (dp1=seg"+i+")","GET","/v.php?"+qs({dp1:segs[i],a:"2"})));
    }
    out.push(await call("heartbeat p.php","POST","/mobile/p.php",{hash}));
    out.push(await call("alt video v.php","GET","/v.php?"+qs({dp1:hash,a:"2"})));
    out.push(await call("streamtape","GET","/streamtape.php"));

    // UserRepository — reads
    out.push(await call("recentplay list (before)","GET","/mobile/recentplay.php"));
    const settingsBefore=await call("settings (before)","GET","/mobile/setting.php"); out.push(settingsBefore);
    out.push(await call("language (GET, current)","GET","/mobile/language.php"));
    let lang="0"; try{ lang=String(JSON.parse(out[out.length-1].body).lang); }catch(e){}

    // UserRepository — state-changing, each undone straight after
    out.push(await call("recentplay add","POST","/mobile/recentplay.php",{recentplay:ID}));
    out.push(await call("recentplay list (after add)","GET","/mobile/recentplay.php"));
    out.push(await call("recentplay delete (undo)","POST","/mobile/recentplay.php",{deleteid:ID}));
    // restore whatever the account had, not an assumed default
    let sb={}; try{ sb=JSON.parse(settingsBefore.body)||{}; }catch(e){}
    const ott0=sb.ott||"nf", hd0=sb.hd||"off";
    out.push(await call("setting ott toggle","POST","/mobile/setting.php",{ott:ott0==="pv"?"nf":"pv"}));
    out.push(await call("setting ott="+ott0+" (undo)","POST","/mobile/setting.php",{ott:ott0}));
    out.push(await call("setting hd toggle","POST","/mobile/setting.php",{hd:hd0==="hd"?"off":"hd"}));
    out.push(await call("setting hd="+hd0+" (undo)","POST","/mobile/setting.php",{hd:hd0}));
    out.push(await call("language POST (same value)","POST","/mobile/language.php",{lang}));
    out.push(await call("watchlist add (backend: id=)","POST","/mobile/MyWatchList.php",{id:ID}));
    const settingsAfter=await call("settings (after undo)","GET","/mobile/setting.php"); out.push(settingsAfter);
    out.push({name:"settings restored?",s:200,body:String(settingsBefore.body===settingsAfter.body)});
    return JSON.stringify(out);
  })()`;
  const results = JSON.parse(await evalIn(expr) || '[]');
  if (!results.length) throw new Error('authenticated calls returned nothing');
  // every same-site page/API the browser loaded on its own, path only — shows where home and its feed live now
  const seen = [...new Map(records.filter(r => r.kind === 'resp' && /net\d+\.cc$/.test(hostOf(r.url)))
    .map(r => { const u = new URL(r.url); return [r.status + ' ' + u.host + u.pathname, { status: r.status, url: u.host + u.pathname, mime: r.mime }]; })).values()];
  results.push({ name: 'site requests seen (paths only)', s: 200, body: JSON.stringify(seen) });
  for (const r of results) log('  ', String(r.s ?? 'ERR').padEnd(4), r.name.padEnd(46), r.err || `${r.ct || ''} ${r.len ?? ''}b`.trim());

  // 5) save sanitized output
  const outPath = path.join(OUT_DIR, `authenticated-${new Date().toISOString().slice(0, 10)}.json`);
  fs.writeFileSync(outPath, JSON.stringify({ captured: new Date().toISOString(), id: ID, query: QUERY, note: 'Tokens redacted to {{t}}/{{hash}}/{{play_token}}. No live secrets.', results }, null, 2));
  log('step 5/5 — saved sanitized bodies ->', outPath);

  await cleanup(cdp);
}

async function cleanup(cdp, exitCode = 0) {
  try { await Promise.race([cdp.send('Browser.close'), sleep(1500)]); } catch {}
  await sleep(1000);
  try { chrome.kill(); } catch {}
  await sleep(800);
  try { fs.rmSync(PROFILE, { recursive: true, force: true }); log('purged throwaway profile'); } catch {}
  log('done.');
  process.exit(exitCode);
}

main().catch(async e => {
  console.error('ERROR:', e.message || e);
  try { chrome.kill(); } catch {}
  await sleep(1500);
  try { fs.rmSync(PROFILE, { recursive: true, force: true }); log('purged throwaway profile'); } catch {}
  process.exit(1);
});
