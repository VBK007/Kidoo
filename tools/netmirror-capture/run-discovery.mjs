// Supervisor for discover-ott-feed.mjs — stay automatic, re-earn only when the session fails.
//
// The discovery probe runs headless on a saved session with no human. A NetMirror session is
// short-lived (cf_clearance ~30 min, IP/UA-bound; the daily ad-gate t_hash_t), so sooner or later a
// run comes back "session expired" (exit 2). THAT is the only moment a human is needed: this
// supervisor then opens the visible gate once, lets you do the single honest ad-view, saves the
// fresh session, and goes back to headless. Every other run is hands-free.
//
// What it will NOT do: fake the ad impression or defeat Cloudflare. Re-earning a dead session is a
// real ad-view in a real window — the one step that can't be automated by design.
//
// Usage:
//   node run-discovery.mjs                      # one cycle: auto if a session exists, else earn it
//   node run-discovery.mjs --watch 1500         # loop every 1500s; re-earn only when the session dies
//   node run-discovery.mjs --otts nf            # forwarded to the probe
//   node run-discovery.mjs --session mine.txt   # session file (default cdp-capture/session.txt)

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const args = Object.fromEntries(process.argv.slice(2).reduce((a, v, i, arr) => {
  if (v.startsWith('--')) a.push([v.slice(2), arr[i + 1]]); return a;
}, []));

const PROBE = path.join(__dirname, 'discover-ott-feed.mjs');
const SELFCHECK = 'selfcheck' in args;
const SESSION = path.resolve(args.session || path.join(__dirname, 'cdp-capture', 'session.txt'));
const WATCH = args.watch !== undefined ? Number(args.watch) : 0; // seconds; 0 = run once
// Proactive rotation: mint a brand-new session NOW, even though the current one may still work.
// The backend keeps serving the old session until this writes a fresh one, so there is no gap.
// Without this, a cycle on a still-valid session.txt only runs the headless auto-probe and never
// re-earns — so the held t_hash_t is never rotated and dies at its hard TTL.
const FORCE_EARN = 'force-earn' in args;
const EXIT_SESSION_EXPIRED = 2;
const passthrough = [];
for (const k of ['otts', 'chrome', 'port', 'cookie-host', 'ua', 'gate-timeout']) {
  if (args[k] !== undefined) passthrough.push(`--${k}`, args[k]);
}
fs.mkdirSync(path.dirname(SESSION), { recursive: true });

const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[sup]', ...a);

// Run the probe and resolve with its exit code. stdio is inherited so the human sees the gate
// prompt and the sweep output directly.
function runProbe(mode) {
  const common = ['--save-session', SESSION, ...passthrough];
  const argv = mode === 'auto'
    ? [PROBE, '--headless', '--cookie-file', SESSION, ...common]  // reuse the saved session, no window
    : [PROBE, ...common];                                        // visible window, human earns a new one
  log(mode === 'auto' ? 'automatic run (reusing saved session, headless)…'
                       : 'session needs earning — opening the gate for ONE ad-view…');
  return new Promise(res => {
    const c = spawn(process.execPath, argv, { stdio: 'inherit', cwd: __dirname });
    c.on('exit', code => res(code ?? 1));
  });
}

async function oneCycle() {
  if (FORCE_EARN) {
    // Rotation path: do NOT reuse the (possibly still-valid) saved session. Open the gate, earn a
    // brand-new one now, then confirm it headless. Returns true only on a genuine fresh earn.
    log('force-earn — rotating ahead of TTL: opening the gate for ONE ad-view to mint a new session.');
    const earned = await runProbe('earn');
    if (earned !== 0) { log(`could not earn a session (exit ${earned}).`); return false; }
    const code = await runProbe('auto');
    return code === 0;
  }
  const haveSession = fs.existsSync(SESSION) && fs.readFileSync(SESSION, 'utf8').includes('t_hash_t=');
  if (haveSession) {
    const code = await runProbe('auto');
    if (code === 0) return true;
    if (code !== EXIT_SESSION_EXPIRED) { log(`probe failed (exit ${code}) — not a session issue; leaving it alone.`); return false; }
    log('saved session is dead — re-earning it now.');
  } else {
    log('no saved session yet — earning the first one.');
  }
  // the one human step: earn a fresh session, which the probe saves to SESSION on success
  const earned = await runProbe('earn');
  if (earned !== 0) { log(`could not earn a session (exit ${earned}).`); return false; }
  // straight back to headless with the freshly-saved session
  const code = await runProbe('auto');
  return code === 0;
}

async function main() {
  if (SELFCHECK) {
    // Preflight only: prove Chrome + CDP + reachability, then exit. No session, no gate, no loop.
    const extra = [];
    for (const k of ['chrome', 'port', 'cookie-host', 'ua']) if (args[k] !== undefined) extra.push(`--${k}`, args[k]);
    const c = spawn(process.execPath, [PROBE, '--selfcheck', ...extra], { stdio: 'inherit', cwd: __dirname });
    c.on('exit', code => process.exit(code ?? 1));
    return;
  }
  if (!WATCH) { const ok = await oneCycle(); process.exit(ok ? 0 : 1); }
  log(`watch mode — a cycle every ${WATCH}s; a human is asked only when the session has failed.`);
  for (;;) {
    try { await oneCycle(); } catch (e) { log('cycle error:', e.message || e); }
    log(`sleeping ${WATCH}s…`);
    await sleep(WATCH * 1000);
  }
}

main();
