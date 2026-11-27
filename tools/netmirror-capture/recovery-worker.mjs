import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { chmod, lstat, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

process.umask(0o077);
const socketDirectory = '/run/kidoo-recovery';
const stateDirectory = '/var/lib/kidoo-worker';
const socketPath = join(socketDirectory, 'acquisition.sock');
const fixture = process.argv[2] === '--synthetic-fixture';
const id = process.getuid();

for (const directory of [socketDirectory, stateDirectory]) {
  const stat = await lstat(directory);
  if (!stat.isDirectory() || stat.uid !== id || (stat.mode & 0o077)) throw new Error('Private worker directory required');
}

// The entrypoint holds an exclusive flock before stale socket reconciliation.
await rm(socketPath, { force: true });
const previous = await readFile(join(stateDirectory, 'state.json'), 'utf8')
  .catch(error => { if (error.code !== 'ENOENT') throw error; return '{}'; });
await record(JSON.parse(previous).state === 'ACQUIRING' ? 'INTERRUPTED' : 'IDLE');
let active;

async function record(state) {
  const temp = join(stateDirectory, 'state.next');
  await writeFile(temp, JSON.stringify({ state, timestamp: new Date().toISOString() }), { mode: 0o600 });
  await rename(temp, join(stateDirectory, 'state.json'));
}

// Local synthetic browser fixture. Production acquisition stays closed until a provider
// supplies an authorized unattended protocol with a documented credential response.
async function acquireFixture(signal) {
  const profile = await mkdtemp('/tmp/kidoo-browser-');
  try {
    const page = join(profile, 'fixture.html');
    await writeFile(page, '<title>Kidoo synthetic recovery fixture</title>');
    const browser = spawn('/usr/bin/chromium', [
      '--headless', '--disable-background-networking', '--no-first-run',
      '--no-default-browser-check', '--dump-dom', '--user-data-dir=' + profile,
      'file://' + page,
    ], {
      detached: true,
      stdio: ['ignore', 'pipe', 'inherit'],
      env: { ...process.env, XDG_CONFIG_HOME: profile, XDG_CACHE_HOME: profile },
    });
    const closed = new Promise(resolve => {
      browser.once('error', () => resolve(-1));
      browser.once('close', resolve);
    });
    function kill(signal) {
      if (!browser.pid) return;
      try { process.kill(-browser.pid, signal); } catch (err) { if (err.code !== 'ESRCH') throw err; }
    }
    const cancel = () => kill('SIGKILL');
    signal.addEventListener('abort', cancel, { once: true });
    if (signal.aborted) cancel();
    try {
      let found = false;
      browser.stdout.setEncoding('utf8');
      let tail = '';
      for await (const chunk of browser.stdout) {
        const text = tail + chunk;
        found ||= text.includes('<title>Kidoo synthetic recovery fixture</title>');
        tail = text.slice(-100);
      }
      if (await closed !== 0 || signal.aborted || !found) throw new Error('Fixture acquisition failed');
      return 't_hash_t=fixture-new';
    } finally {
      signal.removeEventListener('abort', cancel);
      kill('SIGKILL');
      await closed;
    }
  } finally {
    await rm(profile, { recursive: true, force: true });
  }
}

const server = createServer(async (req, res) => {
  if (req.method !== 'POST' || req.url !== '/acquire' || req.headers['transfer-encoding'] ||
      ![undefined, '0'].includes(req.headers['content-length'])) {
    res.writeHead(400).end();
    return;
  }
  if (!fixture || active) {
    res.writeHead(active ? 409 : 503).end();
    return;
  }
  const controller = new AbortController();
  active = controller;
  const timer = setTimeout(() => controller.abort(), 30000);
  const disconnect = () => controller.abort();
  res.on('close', disconnect);
  try {
    await record('ACQUIRING');
    const candidate = await acquireFixture(controller.signal);
    await record('SUCCEEDED');
    if (!res.destroyed) res.writeHead(200, { 'Cache-Control': 'no-store' }).end(candidate);
  } catch {
    await record(controller.signal.aborted ? 'CANCELLED' : 'FAILED').catch(() => {});
    if (!res.destroyed) res.writeHead(controller.signal.aborted ? 504 : 503).end();
  } finally {
    clearTimeout(timer);
    res.removeListener('close', disconnect);
    active = undefined;
  }
});
server.requestTimeout = 5000;
server.headersTimeout = 5000;
server.listen(socketPath, () => {
  chmod(socketPath, 0o600).catch(() => process.exit(1));
});
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.once(signal, async () => {
    active?.abort();
    server.close();
    while (active) await delay(20);
    await rm(socketPath, { force: true });
    process.exit(0);
  });
}
