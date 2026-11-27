#!/usr/bin/env bash
# Run from the repository. An optional Gradle test selector runs just the integration fixture.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
profile=$(realpath "${1:?supply the validated Chromium seccomp profile}")
shift
image=${KIDOO_TEST_WORKER_IMAGE:-kidoo-recovery-worker:local}
test "$(id -u)" -ne 0
base=$(mktemp -d /tmp/kidoo-integrated-worker.XXXXXX)
worker=kidoo-integrated-worker-$$
cleanup() {
    local result=$?
    if (( result != 0 )); then docker logs "$worker" >&2 || true; fi
    docker rm -f "$worker" >/dev/null 2>&1 || true
    rm -rf -- "$base"
}
trap cleanup EXIT
mkdir -m 700 "$base/socket" "$base/state"
# Match the invoking UID for local fixture access. Production uses fixed UID/GID 1001.
docker run -d --name "$worker" --pull never --network none --init --read-only \
    --user "$(id -u):$(id -g)" --security-opt no-new-privileges --security-opt "seccomp=$profile" \
    --env KIDOO_FIXTURE_BROWSER_TIMEOUT_MS=120000 --cap-drop ALL --cpus 0.75 --memory 512m --memory-swap 512m --pids-limit 160 --shm-size 128m \
    --tmpfs /tmp:rw,nosuid,nodev,mode=1777,size=128m \
    --mount "type=bind,source=$base/socket,target=/run/kidoo-recovery" \
    --mount "type=bind,source=$base/state,target=/var/lib/kidoo-worker" "$image" --synthetic-fixture >/dev/null

export KIDOO_TEST_WORKER_SOCKET="$base/socket/acquisition.sock"
export KIDOO_TEST_WORKER_CONTAINER="$worker"
export KIDOO_TEST_WORKER_NODE="${KIDOO_TEST_WORKER_NODE:-$(command -v node)}"
deadline=$((SECONDS + 60))
until test -S "$KIDOO_TEST_WORKER_SOCKET"; do
    if (( SECONDS >= deadline )); then
        echo 'Fixture worker did not create its socket within 60 seconds' >&2
        exit 1
    fi
    sleep 0.25
done
# Environment changes must not let Gradle reuse results from a run with the fixture disabled.
./gradlew --offline --max-workers=2 :test "$@"
