// CDP browser-based capture for the authorized NetMirror web-flow analysis.
// Reads traffic the browser has ALREADY decrypted via Chrome DevTools Protocol.
// No proxy, no custom CA, no interception of the device. Nothing is bypassed:
// the human at the keyboard solves any Cloudflare challenge and watches the ad.
//
// Usage: node capture.mjs <browserWsUrl> <outDir>
import fs from 'node:fs';
import path from 'node:path';

const WS = process.argv[2];
const OUT = process.argv[3] || '.';
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 10; Core-T4) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36';

// Hosts whose response BODIES are worth keeping (content/API backend + dispatcher).
const BODY_HOSTS = [/net52\.cc$/, /iosmirror\.cc$/, /netmirror\.app$/, /mobiledetect\.app$/, /mobiledetect\.cc$/];
// Everything else (ad networks, CDNs, trackers) -> metadata only, no body.

const jsonlPath = path.join(OUT, 'capture.jsonl');
const out = fs.createWriteStream(jsonlPath, { flags: 'a' });
function rec(o) { out.write(JSON.stringify({ ts: new Date().toISOString(), ...o }) + '\n'); }

function hostOf(u) { try { return new URL(u).host; } catch { return ''; } }
function wantBody(u) { const h = hostOf(u); return BODY_HOSTS.some(r => r.test(h)); }

let nextId = 1;
const pending = new Map();      // msg id -> {resolve, sessionId}
const reqUrl = new Map();       // `${sess}:${requestId}` -> url
const sessions = new Set();

const browser = new WebSocket(WS);

function send(method, params = {}, sessionId) {
  const id = nextId++;
  const msg = { id, method, params };
  if (sessionId) msg.sessionId = sessionId;
  browser.send(JSON.stringify(msg));
  return new Promise((res) => pending.set(id, { res }));
}

browser.addEventListener('open', async () => {
  console.log('[cdp] connected to browser');
  await send('Target.setDiscoverTargets', { discover: true });
  await send('Target.setAutoAttach', { autoAttach: true, waitForDebuggerOnStart: true, flatten: true });
  console.log('[cdp] auto-attach armed. Capturing to', jsonlPath);
});

async function initSession(sessionId, targetInfo) {
  sessions.add(sessionId);
  try {
    await send('Network.enable', { maxResourceBufferSize: 100 * 1024 * 1024, maxTotalBufferSize: 200 * 1024 * 1024 }, sessionId);
    await send('Network.setUserAgentOverride', {
      userAgent: MOBILE_UA,
      acceptLanguage: 'en-US,en;q=0.9',
      platform: 'Android',
      userAgentMetadata: {
        platform: 'Android', platformVersion: '10', architecture: '', model: 'Core-T4',
        mobile: true, brands: [{ brand: 'Chromium', version: '120' }, { brand: 'Google Chrome', version: '120' }],
        fullVersion: '120.0.0.0'
      }
    }, sessionId);
    await send('Page.enable', {}, sessionId).catch(() => {});
  } catch (e) { console.log('[cdp] init err', e.message); }
  // release the paused target so it starts loading
  await send('Runtime.runIfWaitingForDebugger', {}, sessionId).catch(() => {});
  console.log('[cdp] session ready:', targetInfo?.type, targetInfo?.url || '');
}

browser.addEventListener('message', async (ev) => {
  const m = JSON.parse(ev.data);

  // responses to our commands
  if (m.id && pending.has(m.id)) { pending.get(m.id).res(m); pending.delete(m.id); return; }

  const sess = m.sessionId;
  switch (m.method) {
    case 'Target.attachedToTarget':
      await initSession(m.params.sessionId, m.params.targetInfo);
      break;
    case 'Network.requestWillBeSent': {
      const { requestId, request } = m.params;
      reqUrl.set(`${sess}:${requestId}`, request.url);
      rec({ kind: 'request', sess, requestId, method: request.method, url: request.url,
            headers: request.headers, postData: request.postData || null });
      break;
    }
    case 'Network.requestWillBeSentExtraInfo': {
      // associatedCookies + raw request headers (incl Cookie)
      rec({ kind: 'reqExtra', sess, requestId: m.params.requestId, headers: m.params.headers });
      break;
    }
    case 'Network.responseReceived': {
      const { requestId, response } = m.params;
      rec({ kind: 'response', sess, requestId, url: response.url, status: response.status,
            mime: response.mimeType, headers: response.headers });
      break;
    }
    case 'Network.responseReceivedExtraInfo': {
      // raw response headers incl Set-Cookie
      rec({ kind: 'respExtra', sess, requestId: m.params.requestId, headers: m.params.headers });
      break;
    }
    case 'Network.loadingFinished': {
      const { requestId } = m.params;
      const url = reqUrl.get(`${sess}:${requestId}`) || '';
      if (url && wantBody(url)) {
        try {
          const r = await send('Network.getResponseBody', { requestId }, sess);
          if (r.result) rec({ kind: 'body', sess, requestId, url,
                              base64: !!r.result.base64Encoded, body: r.result.body });
        } catch (e) { /* body evicted or binary too large */ }
      }
      break;
    }
  }
});

browser.addEventListener('close', () => { console.log('[cdp] browser ws closed'); out.end(); });
browser.addEventListener('error', (e) => console.log('[cdp] ws error', e.message || e));

process.on('SIGINT', () => { out.end(); process.exit(0); });
console.log('[cdp] capture.mjs starting; target browser ws =', WS);
