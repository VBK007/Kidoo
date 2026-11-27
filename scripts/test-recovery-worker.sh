#!/usr/bin/env bash
# Disposable, network-free worker test. Run after building Dockerfile.recovery-worker.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
profile=$(realpath "${1:?supply the validated Chromium seccomp profile}")
image=${2:-kidoo-recovery-worker:local}
worker=kidoo-worker-fixture-$$
socket=$worker-socket
state=$worker-state
docker volume create "$socket" >/dev/null
docker volume create "$state" >/dev/null
cleanup() {
    if (( $? != 0 )); then docker logs "$worker" >&2 || true; docker exec "$worker" cat /var/lib/kidoo-worker/state.json >&2 || true; fi
    docker rm -f "$worker" >/dev/null 2>&1 || true
    docker volume rm "$socket" "$state" >/dev/null 2>&1 || true
}
trap cleanup EXIT
mounts=(-v "$socket:/run/kidoo-recovery" -v "$state:/var/lib/kidoo-worker")
docker run --rm --network none --user 0 --entrypoint sh "${mounts[@]}" "$image" \
    -c 'chown 1001:1001 /run/kidoo-recovery /var/lib/kidoo-worker; chmod 700 /run/kidoo-recovery /var/lib/kidoo-worker'
start() {
    docker run -d --name "$worker" --network none --init --read-only \
        --security-opt no-new-privileges --security-opt "seccomp=$profile" --cap-drop ALL \
        --cpus 0.75 --memory 512m --memory-swap 512m --pids-limit 160 --shm-size 128m \
        --tmpfs /tmp:rw,nosuid,nodev,mode=1777,size=128m "${mounts[@]}" "$image" "$@" >/dev/null
    for retry in $(seq 1 20); do
        if docker exec "$worker" test -S /run/kidoo-recovery/acquisition.sock; then return; fi
        sleep 0.25
    done
    docker logs "$worker"
    return 1
}

probe() {
    docker run --rm -i --network none -v "$socket:/run/kidoo-recovery:ro" \
        -v "$root/tools/netmirror-capture:/fixture:ro" --entrypoint node "$image" --input-type=module
}

start --synthetic-fixture
probe <<'JS'
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { acquire } from '/fixture/acquire-worker.mjs';
const outcomes = await Promise.all(Array.from({length: 200}, (_, i) => acquire('/tmp/candidate-'+i, 20000)));
assert.equal(outcomes.filter(exit => exit === 0).length, 1);
assert.equal(outcomes.filter(exit => exit === 5).length, 199);
const staged = '/tmp/candidate-'+outcomes.indexOf(0);
assert.equal(await fs.readFile(staged, 'utf8'), 't_hash_t=fixture-new');
assert.equal((await fs.stat(staged)).mode & 0o777, 0o600);
console.log('200 concurrent calls: one staged acquisition, 199 rejected');
assert.equal(await acquire('/tmp/timeout', 20), 3);
await assert.rejects(fs.stat('/tmp/timeout'), {code:'ENOENT'});
JS
for retry in $(seq 1 40); do
    if docker exec "$worker" sh -c 'test -z "$(find /tmp -maxdepth 1 -name "kidoo-browser-*")"'; then break; fi
    test "$retry" -lt 40
    sleep 0.25
done
docker exec "$worker" sh -c 'id -u; cat /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.swap.current /sys/fs/cgroup/memory.events'
# Force an allocation above the worker cgroup limit in the disposable container.
if docker exec "$worker" node -e 'const bytes = Buffer.allocUnsafe(700*1024*1024); bytes.fill(1); console.log(bytes.length)'; then
    echo 'Expected worker memory limit to kill the stress allocation' >&2
    exit 1
fi
docker exec "$worker" awk '$1=="oom_kill" { if ($2<1) exit 1; print "worker_oom_kill=" $2 }' /sys/fs/cgroup/memory.events
docker kill "$worker" >/dev/null
docker rm "$worker" >/dev/null
probe <<'JS'
import assert from 'node:assert/strict';
import { acquire } from '/fixture/acquire-worker.mjs';
assert.equal(await acquire('/tmp/worker-down', 2000),5);
console.log('Unavailable worker reported a local acquisition failure');
JS
start
probe <<'JS'
import assert from 'node:assert/strict';
import { acquire } from '/fixture/acquire-worker.mjs';
assert.equal(await acquire('/tmp/unsupported', 2000),5);
console.log('Worker restarted and rejected acquisition outside fixture mode');
JS
