// Production handoff only. The operator completes the provider's approved flow separately.
// 0 staged, 2 invalid configuration/input, 3 timed out, 4 cancelled, 5 filesystem failure.
import { constants as flags } from 'node:fs';
import { open, lstat } from 'node:fs/promises';
import { basename, dirname, isAbsolute, join } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';

const [output, rawTimeout] = process.argv.slice(2);
const duration = Number(rawTimeout);
const parent = process.ppid;

async function acquire() {
  if (!output || !isAbsolute(output) || !Number.isSafeInteger(duration) || duration <= 0) return 2;
  const inbox = join(dirname(output), 'operator', basename(output));
  const deadline = performance.now() + duration;
  while (performance.now() < deadline) {
    // A container can legitimately run Spring Boot as PID 1.
    if (process.ppid !== parent) return 4;
    try {
      const stat = await lstat(inbox);
      if (!stat.isFile() || stat.uid !== process.getuid() || (stat.mode & 0o077) !== 0 || stat.size > 8192) return 2;
      const source = await open(inbox, flags.O_RDONLY | flags.O_NOFOLLOW);
      let credential;
      try {
        const current = await source.stat();
        if (current.ino !== stat.ino || current.dev !== stat.dev || current.size > 8192) return 2;
        const buffer = Buffer.alloc(8193);
        let offset = 0;
        while (offset < buffer.length) {
          const { bytesRead } = await source.read(buffer, offset, buffer.length - offset, null);
          if (bytesRead === 0) break;
          offset += bytesRead;
        }
        credential = buffer.subarray(0, offset);
      } finally {
        await source.close();
      }
      if (!credential.length || credential.length > 8192) return 2;
      const destination = await open(output, flags.O_WRONLY | flags.O_CREAT | flags.O_EXCL | flags.O_NOFOLLOW, 0o600);
      try {
        await destination.writeFile(credential);
        await destination.sync();
      } finally {
        await destination.close();
        credential.fill(0);
      }
      return 0;
    } catch (error) {
      if (error.code !== 'ENOENT') return 5;
    }
    await sleep(200);
  }
  return 3;
}

process.on('SIGTERM', () => process.exit(4));
process.exitCode = await acquire().catch(() => 5);
