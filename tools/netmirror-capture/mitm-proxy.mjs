// Decrypting MITM proxy for RE of the NetMirror app's home API, on the user's own device.
//
// Prereqs: the app must trust our CA (patched build via apk-mitm) and the CA (mitm-ca/ca.crt)
// installed as a user cert on the device. For hosts covered by mitm-ca/leaf.crt we terminate
// TLS, read the decrypted HTTP, and re-originate to the real server — logging full req+resp.
// Any other host is passed through raw (hostname-only visibility).
//
//   adb reverse tcp:8899 tcp:8899
//   adb shell settings put global http_proxy 127.0.0.1:8899
//   node mitm-proxy.mjs
//   <launch patched NetMirror, open Home>  ->  cdp-capture/mitm-YYYY-MM-DD.jsonl

import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import tls from 'node:tls';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PROXY_PORT || 8899);
const CA = path.join(__dirname, 'mitm-ca');
const leafCtx = tls.createSecureContext({
  key: fs.readFileSync(path.join(CA, 'leaf.key')),
  cert: fs.readFileSync(path.join(CA, 'leaf.crt')),
});
const MITM_HOSTS = /(^|\.)(net52\.cc|net77\.cc|mobiledetect\.app|imgcdn\.kim)$/i;

const OUTDIR = path.join(__dirname, 'cdp-capture');
fs.mkdirSync(OUTDIR, { recursive: true });
const OUT = path.join(OUTDIR, `mitm-${new Date().toISOString().slice(0, 10)}.jsonl`);
const sink = fs.createWriteStream(OUT, { flags: 'a' });
const rec = o => sink.write(JSON.stringify({ ts: new Date().toISOString(), ...o }) + '\n');
const log = (...a) => console.log('[mitm]', ...a);
const INTEREST = /\.php|\/mobile\/|home|verify|setting|post|search|check\.php|token/i;

// cookie value redaction for console (full value still in the jsonl file, which is gitignored)
const redact = h => {
  const c = { ...h };
  if (c.cookie) c.cookie = c.cookie.replace(/(t_hash_t|cf_clearance)=([^;]{6})[^;]*/gi, '$1=$2…');
  return c;
};

// Forward a decrypted request to the real origin over TLS and stream the response back.
function onDecrypted(creq, cres) {
  const host = (creq.headers.host || '').split(':')[0];
  const chunks = [];
  creq.on('data', d => chunks.push(d));
  creq.on('end', () => {
    const body = Buffer.concat(chunks);
    const hot = INTEREST.test(creq.url);
    if (hot) log(`>>> ${creq.method} https://${host}${creq.url}  (body ${body.length}b)`);
    rec({ kind: 'req', scheme: 'https', method: creq.method, host, path: creq.url,
      headers: redact(creq.headers), bodyLen: body.length,
      body: body.length && body.length < 16384 ? body.toString('utf8') : undefined });

    const headers = { ...creq.headers };
    const opts = { host, port: 443, method: creq.method, path: creq.url, headers, servername: host };
    const preq = https.request(opts, pres => {
      const rc = [];
      pres.on('data', d => rc.push(d));
      pres.on('end', () => {
        const rbody = Buffer.concat(rc);
        const ct = pres.headers['content-type'] || '';
        const text = /json|text|html|javascript|xml/i.test(ct);
        rec({ kind: 'resp', host, path: creq.url, status: pres.statusCode, ct, len: rbody.length,
          setCookie: pres.headers['set-cookie'],
          body: text && rbody.length < 60000 ? rbody.toString('utf8') : undefined });
        if (hot) log(`<<< ${pres.statusCode} ${ct} ${rbody.length}b  ${host}${creq.url}`);
      });
      cres.writeHead(pres.statusCode, pres.headers);
      pres.pipe(cres);
    });
    preq.on('error', e => { rec({ kind: 'err', host, path: creq.url, err: e.message });
      try { cres.writeHead(502); cres.end(e.message); } catch {} });
    if (body.length) preq.write(body);
    preq.end();
  });
}
const decServer = http.createServer(onDecrypted);
decServer.on('clientError', (e, s) => { try { s.end(); } catch {} });

// Outer proxy: plain-HTTP requests + CONNECT (MITM for our hosts, passthrough otherwise)
const proxy = http.createServer((creq, cres) => {
  // plain HTTP absolute-form
  let host, port = 80, pathPart = creq.url;
  try { const u = new URL(creq.url); host = u.hostname; port = Number(u.port) || 80; pathPart = u.pathname + u.search; }
  catch { host = (creq.headers.host || '').split(':')[0]; }
  log(`HTTP ${creq.method} ${creq.url}`);
  rec({ kind: 'req', scheme: 'http', method: creq.method, host, path: pathPart, headers: redact(creq.headers) });
  const preq = http.request({ host, port, method: creq.method, path: pathPart, headers: creq.headers }, pres => {
    const rc = []; pres.on('data', d => rc.push(d));
    pres.on('end', () => { const b = Buffer.concat(rc); const ct = pres.headers['content-type'] || '';
      rec({ kind: 'resp', scheme: 'http', host, path: pathPart, status: pres.statusCode, ct, len: b.length,
        body: /json|text|html/i.test(ct) && b.length < 60000 ? b.toString('utf8') : undefined }); });
    cres.writeHead(pres.statusCode, pres.headers); pres.pipe(cres);
  });
  preq.on('error', e => { try { cres.writeHead(502); cres.end(); } catch {} });
  creq.pipe(preq);
});

proxy.on('connect', (creq, csock, head) => {
  const [host, port = '443'] = creq.url.split(':');
  if (MITM_HOSTS.test(host)) {
    log(`CONNECT ${host}  (MITM — decrypting)`);
    rec({ kind: 'connect', host, port: Number(port), mitm: true });
    csock.write('HTTP/1.1 200 Connection Established\r\n\r\n');
    const tsock = new tls.TLSSocket(csock, { isServer: true, secureContext: leafCtx });
    tsock.on('error', e => rec({ kind: 'tls-err', host, err: e.message }));
    if (head && head.length) tsock.unshift(head);
    decServer.emit('connection', tsock);
  } else {
    rec({ kind: 'connect', host, port: Number(port), mitm: false });
    const up = net.connect(Number(port), host, () => {
      csock.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      if (head && head.length) up.write(head);
      up.pipe(csock); csock.pipe(up);
    });
    up.on('error', () => { try { csock.end(); } catch {} });
    csock.on('error', () => { try { up.end(); } catch {} });
  }
});

proxy.on('clientError', (e, s) => { try { s.end('HTTP/1.1 400 Bad Request\r\n\r\n'); } catch {} });
process.on('SIGINT', () => { log('log ->', OUT); sink.end(() => process.exit(0)); });
proxy.listen(PORT, '127.0.0.1', () => log(`MITM listening 127.0.0.1:${PORT}; decrypting ${MITM_HOSTS} -> ${OUT}`));
