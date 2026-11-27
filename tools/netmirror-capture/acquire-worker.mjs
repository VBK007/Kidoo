// Coordinator side of the local acquisition protocol. Only this process can stage the candidate.
import { request } from 'node:http';
import { constants } from 'node:fs';
import { open } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import { pathToFileURL } from 'node:url';

export async function acquire(output, timeout, socketPath = '/run/kidoo-recovery/acquisition.sock') {
  if (!output || !isAbsolute(output) || !Number.isSafeInteger(timeout) || timeout <= 0) return 2;
  const signal = AbortSignal.timeout(Math.min(timeout, 300000));
  try {
    const candidate = await new Promise((resolve, reject) => {
      const call = request({ socketPath, path: '/acquire', method: 'POST', signal }, response => {
        if (response.statusCode !== 200) {
          response.resume();
          resolve(response.statusCode === 504 ? 3 : null);
          return;
        }
        const chunks = [];
        let size = 0;
        response.on('data', chunk => {
          size += chunk.length;
          if (size > 8192) response.destroy(new Error('candidate too large'));
          else chunks.push(chunk);
        });
        response.on('error', reject);
        response.on('end', () => resolve(Buffer.concat(chunks)));
      });
      call.on('error', reject);
      call.end();
    });
    if (candidate === 3) return 3;
    if (!candidate?.length || signal.aborted) return signal.aborted ? 3 : 5;
    try {
      const file = await open(output, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
      try {
        await file.writeFile(candidate);
        await file.sync();
      } finally {
        await file.close();
      }
      return 0;
    } finally {
      candidate.fill(0);
    }
  } catch {
    return signal.aborted ? 3 : 5;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.on('SIGTERM', () => process.exit(4));
  process.exitCode = await acquire(process.argv[2], Number(process.argv[3]));
}
