// Live-traffic capture proxy for reverse-engineering the NetMirror app's home API.
//
// The app declares usesCleartextTraffic=true and ships NO networkSecurityConfig, and the
// device is Android 10 (user CAs not trusted, not rooted) — so we CANNOT decrypt its HTTPS
// bodies. What we CAN read without touching device trust:
//   - every HTTPS CONNECT target  -> the hostname:port the app contacts (plaintext handshake line)
//   - every plain-HTTP request    -> full method/URL/headers/body AND the response
//
// Wiring (USB, no same-WiFi needed):
//   adb reverse tcp:8080 tcp:8080
//   adb shell settings put global http_proxy 127.0.0.1:8080
//   node capture-proxy.mjs                      # this process
//   <launch NetMirror, open Home>
//   adb shell settings delete global http_proxy # restore when done
//
// Writes a JSONL log to cdp-capture/live-YYYY-MM-DD.jsonl and prints a running summary.

import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PROXY_PORT || 8080);
const OUTDIR = path.join(__dirname, 'cdp-capture');
fs.mkdirSync(OUTDIR, { recursive: true });
const OUT = path.join(OUTDIR, `live-${new Date().toISOString().slice(0, 10)}.jsonl`);
const sink = fs.createWriteStream(OUT, { flags: 'a' });
const rec = o => sink.write(JSON.stringify({ ts: new Date().toISOString(), ...o }) + '\n');

const hostHits = new Map();            // host:port -> count (HTTPS CONNECT tunnels)
const noteHost = hp => { hostHits.set(hp, (hostHits.get(hp) || 0) + 1); };
const INTEREST = /net\d*\.cc|netmirror|mobiledetect|imgcdn|streamtape|\.php/i;
const log = (...a) => console.log('[proxy]', ...a);

// --- plain HTTP: full visibility ---
const server = http.createServer((creq, cres) => {
  const url = creq.url;                           // absolute-form URL in proxy mode
  let host, port = 80, pathPart = url;
  try { const u = new URL(url); host = u.hostname; port = Number(u.port) || 80; pathPart = u.pathname + u.search; }
  catch { host = (creq.headers.host || '').split(':')[0]; port = 80; }
  const chunks = [];
  creq.on('data', d => chunks.push(d));
  creq.on('end', () => {
    const body = Buffer.concat(chunks);
    const hot = INTEREST.test(url) || INTEREST.test(host || '');
    log(`HTTP ${creq.method} ${url}${hot ? '   <== INTEREST' : ''}`);
    rec({ kind: 'http-req', method: creq.method, host, port, path: pathPart, url,
      headers: creq.headers, bodyLen: body.length,
      body: body.length && body.length < 8192 ? body.toString('utf8') : undefined });

    const opts = { host, port, method: creq.method, path: pathPart, headers: creq.headers };
    const preq = http.request(opts, pres => {
      const rc = [];
      pres.on('data', d => rc.push(d));
      pres.on('end', () => {
        const rbody = Buffer.concat(rc);
        const ct = pres.headers['content-type'] || '';
        rec({ kind: 'http-resp', host, path: pathPart, status: pres.statusCode, ct,
          len: rbody.length,
          body: /json|text|html|javascript/i.test(ct) && rbody.length < 20000 ? rbody.toString('utf8') : undefined });
        if (hot) log(`   -> ${pres.statusCode} ${ct} ${rbody.length}b`);
      });
      cres.writeHead(pres.statusCode, pres.headers);
      pres.pipe(cres);
    });
    preq.on('error', e => { rec({ kind: 'http-err', host, path: pathPart, err: e.message }); try { cres.writeHead(502); cres.end(); } catch {} });
    if (body.length) preq.write(body);
    preq.end();
  });
});

// --- HTTPS: tunnel, read only the hostname ---
server.on('connect', (creq, csock, head) => {
  const [host, port = '443'] = creq.url.split(':');
  noteHost(`${host}:${port}`);
  const hot = INTEREST.test(host);
  log(`CONNECT ${host}:${port}${hot ? '   <== INTEREST' : ''}`);
  rec({ kind: 'connect', host, port: Number(port) });
  const up = net.connect(Number(port), host, () => {
    csock.write('HTTP/1.1 200 Connection Established\r\n\r\n');
    if (head && head.length) up.write(head);
    up.pipe(csock); csock.pipe(up);
  });
  up.on('error', e => { rec({ kind: 'connect-err', host, err: e.message }); try { csock.end(); } catch {} });
  csock.on('error', () => { try { up.end(); } catch {} });
});

server.on('clientError', (e, sock) => { try { sock.end('HTTP/1.1 400 Bad Request\r\n\r\n'); } catch {} });

process.on('SIGINT', () => {
  log('\n==== hosts contacted (HTTPS CONNECT) ====');
  [...hostHits.entries()].sort((a, b) => b[1] - a[1]).forEach(([h, n]) => log(`  ${n}x  ${h}`));
  log('log saved ->', OUT);
  sink.end(() => process.exit(0));
});

server.listen(PORT, '127.0.0.1', () => log(`listening on 127.0.0.1:${PORT} — log -> ${OUT}\n(Ctrl-C for host summary)`));
