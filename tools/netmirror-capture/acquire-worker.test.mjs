import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { lstat, mkdtemp, readFile, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { acquire } from './acquire-worker.mjs';

test('stages exclusively, rejects replacement and leaves the active file untouched', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'kidoo-adapter-'));
  const socket = join(directory, 'socket');
  const output = join(directory, 'candidate');
  const active = join(directory, 'active');
  const server = createServer((req, res) => res.end('fixture_session=synthetic'));
  try {
    await new Promise(resolve => server.listen(socket, resolve));
    assert.equal(await acquire(active, 2000, socket), 0);
    await symlink(active, output);
    assert.equal(await acquire(output, 2000, socket), 5);
    await rm(output);
    assert.equal(await acquire(output, 2000, socket), 0);
    assert.equal((await lstat(output)).mode & 0o777, 0o600);
    assert.equal(await acquire(output, 2000, socket), 5);
    assert.equal(await readFile(active, 'utf8'), 'fixture_session=synthetic');
  } finally {
    await new Promise(resolve => server.close(resolve));
    await rm(directory, { recursive: true, force: true });
  }
});

test('worker failure, an oversized candidate and timeout leave no staged file', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'kidoo-adapter-'));
  const socket = join(directory, 'socket');
  const output = join(directory, 'candidate');
  let respond = res => res.writeHead(503).end();
  const server = createServer((req, res) => respond(res));
  try {
    await new Promise(resolve => server.listen(socket, resolve));
    assert.equal(await acquire(output, 2000, socket), 5);
    respond = res => res.end('x'.repeat(8193));
    assert.equal(await acquire(output, 2000, socket), 5);
    respond = () => {};
    assert.equal(await acquire(output, 40, socket), 3);
    await assert.rejects(lstat(output), { code: 'ENOENT' });
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    assert.equal(await acquire(output, 2000, socket), 5);
    await rm(directory, { recursive: true, force: true });
  }
});
