# Authorized mirror session recovery on Ubuntu

Recovery is disabled by default. Enable it only after the provider documents an exact credential-expiry response and an authorized, read-only credential validation endpoint that returns a positive success marker. The operator must use the provider's official interface, including any required human verification. No unattended renewal adapter is configured. The production process only stages a credential supplied by the operator.

The older capture and discovery tools are diagnostic tools. Keep them, their output, and any `device-profiles.json` fixtures out of the production service. The production handoff script needs Node.js 22 or later and uses no npm packages, browser, or device profiles.

## Initial provisioning

Use the actual Ubuntu systemd unit and service account in place of `kidoo.service` and `kidoo`. Install JDK 21 and a trusted Ubuntu-compatible Node.js 22+ build. Verify the paths below on the deployment host.

```bash
node --version
java -version
sudo install -d -m 0700 -o kidoo -g kidoo \
  /var/lib/kidoo/mirror /var/lib/kidoo/mirror/staging /var/lib/kidoo/mirror/staging/operator
sudo install -m 0644 -o root -g root \
  tools/netmirror-capture/acquire-session.mjs /opt/kidoo/acquire-session.mjs
sudo install -m 0600 -o kidoo -g kidoo \
  /secure/operator/authorized-session.txt /var/lib/kidoo/mirror/session.txt
sudo install -m 0600 -o root -g root /dev/null /etc/kidoo-mirror-recovery.env
sudoedit /etc/kidoo-mirror-recovery.env
```

Provision the initial file through the provider's approved workflow. Keep the live file, journal and staging paths on a local filesystem that supports POSIX modes, file locking, atomic rename and directory fsync. Directory symlinks are rejected. The service owns the private directories and writes the active file after bootstrap. An existing systemd mount or sandbox policy must allow writes under `/var/lib/kidoo/mirror`.

Fill the values marked below from the provider agreement or deployment. Keep the existing entries for approved origins used by authorized playback. Include the credential endpoints' origins in that allowlist.

```ini
MIRROR_SESSION_FILE=/var/lib/kidoo/mirror/session.txt
MIRROR_RECOVERY_ENABLED=true
MIRROR_RECOVERY_NODE=/usr/bin/node
MIRROR_RECOVERY_SCRIPT=/opt/kidoo/acquire-session.mjs
MIRROR_RECOVERY_WORKING_DIRECTORY=/var/lib/kidoo/mirror
MIRROR_RECOVERY_STAGING_DIRECTORY=/var/lib/kidoo/mirror/staging
MIRROR_RECOVERY_METADATA_PATH=/var/lib/kidoo/mirror/recovery.json
MIRROR_RECOVERY_ACQUISITION_TIMEOUT=5m
MIRROR_RECOVERY_VALIDATION_TIMEOUT=10s
MIRROR_RECOVERY_FAILURE_COOLDOWN=1m
MIRROR_RECOVERY_EXPIRY_ENDPOINT=<exact documented HTTPS URI>
MIRROR_RECOVERY_EXPIRY_STATUS=<documented definitive 4xx status>
MIRROR_RECOVERY_EXPIRY_HEADER=<documented response header>
MIRROR_RECOVERY_EXPIRY_VALUE=<documented definitive expiry value>
MIRROR_RECOVERY_VALIDATION_ENDPOINT=<approved read-only HTTPS GET URI>
MIRROR_RECOVERY_VALIDATION_HEADER=<documented positive verification header>
MIRROR_RECOVERY_VALIDATION_VALUE=<documented positive value>
```

Use a systemd drop-in created by `sudo systemctl edit kidoo.service`.

```ini
[Service]
EnvironmentFile=/etc/kidoo-mirror-recovery.env
```

Then apply the service configuration.

```bash
sudo systemctl daemon-reload
sudo systemctl restart kidoo.service
sudo systemctl status kidoo.service --no-pager
sudo journalctl -u kidoo.service -n 50 --no-pager
```

The status, header, and URI must all match before recovery starts. Generic `403`, `412`, `429`, challenge responses, network errors, and overload do not qualify. `MIRROR_REVOCATION_STATUS` remains the legacy state-only setting when recovery is disabled. Configured endpoints use HTTPS, with HTTP loopback reserved for tests. Credential validation does one GET through the existing managed HTTP limiter. It requires a successful status and the configured positive header, and follows no redirects.

## Operator handoff during an incident

Call `GET /api/admin/mirror/recovery` with the existing parent account and admin-key authorization. The sanitized status contains the current attempt ID. Use your existing authenticated admin client or local secret-safe tooling. When the process is waiting, obtain a fresh credential from the approved provider interface and stage it under the attempt ID. In this example `attempt` contains the UUID from the authenticated status response.

```bash
inbox=/var/lib/kidoo/mirror/staging/operator
sudo install -m 0600 -o kidoo -g kidoo \
  /secure/operator/authorized-replacement.txt "$inbox/.$attempt.pending"
sudo -u kidoo mv -T -- "$inbox/.$attempt.pending" "$inbox/$attempt.cookie"
```

Spring starts one Node subprocess for the incident. Node exits with 0 for a staged candidate, 2 for invalid input, 3 for timeout, 4 for cancellation, or 5 for a filesystem failure. Spring owns validation and activation. Never direct the Node process or the operator handoff at the live file. Remove any separate source copies according to the credential-handling policy.

Use `POST /api/admin/mirror/recovery/cancel` to cancel an active acquisition. After failure or timeout, authorized administrators may call `POST /api/admin/mirror/recovery/retry` after the configured cooldown. A retry has a new attempt ID under the same incident. Submission must use the new ID. Structured journal events and the recovery-state metrics can feed the existing admin alerting system. No external notification transport is configured.

## Restart, continuity, and rollback

Only one process can own the recovery lock for a configured journal. An interrupted acquisition becomes a failed incident on restart with its ID preserved. It waits for an explicit retry. If the process died after the atomic rename, the journal and active file are reconciled instead of requesting another credential. A conflicting out-of-band file edit blocks activation. Treat the service as the sole live-file writer while enabled.

New starts use the validated generation. The service does not change the variant signing key or restart existing players during recovery. Existing playback still depends on the provider accepting its already issued URLs.

To roll back, first stop new mirror starts at ingress or use a known-valid, operator-approved credential. Set `MIRROR_RECOVERY_ENABLED=false` and restart the service with the prior artifact if needed. The original session-file polling mode becomes available again. Retain the private journal for investigation. Disabling recovery by itself does not protect against loading a revoked file in the legacy mode, so remove or replace that credential first. The Cloudflare tunnel and Firebase endpoint discovery require no changes.

### Isolated Chromium runtime

The optional recovery image runs Chromium as UID 1001. On Linux amd64, generate the
sandbox test profile from the pinned [Playwright baseline](https://github.com/microsoft/playwright/blob/v1.58.0/utils/docker/seccomp_profile.json).

```bash
curl --fail --silent --show-error \
  https://raw.githubusercontent.com/microsoft/playwright/v1.58.0/utils/docker/seccomp_profile.json \
  | python3 scripts/build-recovery-seccomp.py > chromium-recovery-seccomp.json
```

Use the generated file with `--security-opt seccomp=/absolute/path/chromium-recovery-seccomp.json`,
`--cap-drop ALL`, and `--security-opt no-new-privileges`. Keep AppArmor and user namespace
support enabled. The profile keeps the default deny behavior and narrows the added namespace
permissions to Chromium's user, PID and network namespaces. It allows the kernel to enforce
`chroot` permissions after the browser has entered its sandbox. Keep CDP on loopback.
This profile was tested on an isolated container. Browser sandboxing does not establish
authorization for automated verification.

### Catalogue maintenance gates

Generation namespaces with the current `g2` prefix are swept in batches of up to 16 snapshot
rows after hard expiry. The sweep excludes the active session and other configured scopes.
Legacy `g1` rows have no attributable scope in the key and are left untouched. Newly
published snapshots use the existing schema and do not import legacy entries automatically.

Background refresh stays disabled by default. Enable it only with confirmed provider storage
and automated refresh approval. Configure a non-secret
`app.mymirror.catalogue.background-refresh.authorization-reference`, one approved
`ott`, an approved `max-requests-per-run` between 1 and 32, and an `interval` of at least one
minute. An enabled flag without all these settings admits no work. Each eligible tick has one
worker, no queued runs, a three-second upstream budget, and the shared provider limiter.
It skips fresh snapshots and yields when the upstream slot or pacing interval is occupied.
