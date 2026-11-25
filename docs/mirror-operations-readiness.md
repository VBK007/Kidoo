# Kidoo mirror operations and readiness

## What the backend can establish

A cold HLS resolution can issue a heartbeat, a title open, a playlist request, and a master request. A cold token lookup can add verification traffic. The raw playlist and heartbeat routes are separate API calls, so a client using them as well as the HLS resolver can duplicate some work. Catalogue enrichment has its own bounded executor and can consume spare authorized upstream capacity. Scheduled global keep-alive is separate from player lifecycle and defaults to two minutes. Throttling suppresses new upstream calls for the cooldown, and detected access challenges are not retried.

Playback requests are logged with a UUID, operation, ID source, status and duration. The same UUID can correlate API calls when the client supplies it. The dashboard's active count covers Kidoo playback HTTP requests. A native player can remain active after those requests finish and can fetch media directly from a CDN outside Kidoo. Without a mobile trace and the authorized provider's connection semantics, duplicate native players, an unreleased previous player, and a provider stream cap remain hypotheses. No evidence establishes the shared credential as the cause of the live warning.

The backend reuses the resolved origin for the heartbeat token lookup. It preserves the startup sequence, scoped cache keys, and the existing provider limits. Distinct cold starts cannot safely share signed manifests. Equivalent cold starts already share the scoped manifest future when admitted. Moving that sharing ahead of executor admission would require independent waiter deadlines and cancellation ownership. The current bulkhead continues to favor core API responsiveness.

## Ubuntu deployment checks

Follow the [session recovery runbook](mirror-session-recovery.md) for POSIX permissions, Node handoff installation, provider configuration, systemd settings, and rollback. The existing Cloudflare quick-tunnel automation and Firebase endpoint discovery stay in place. This deployment does not introduce a new service or datastore.

1. Verify Java 21 and Node.js 22 or newer under the actual service account. Recovery does not require a browser or npm dependencies. Build the backend with the existing Gradle workflow. Build the optional admin console from `admin-web` using its lockfile and `npm run build`.
2. Verify the operator credential file has mode `0600` and belongs to the service account. Recovery directories must have mode `0700`, resolve without symlinks, and support atomic rename and file locking. Restrict staging and diagnostic artifacts to their intended environments.
3. Provision `MIRROR_VARIANT_SIGNING_KEY` with at least 32 random bytes encoded in base64 through the existing secret channel. Keep it stable across restarts. Use `MIRROR_VARIANT_VERIFICATION_KEYS` for the overlap period during rotation. Keep approved origins, provider limits, and documented recovery discriminators in the protected service environment.
4. Verify local health at the configured port before testing the existing Firebase-discovered URL. Use an authenticated admin account with the administrator key for the mirror dashboard. An application health response does not demonstrate upstream entitlement.
5. Inspect structured playback and recovery logs. Alert on rejection, cooldown, failed or timed-out recovery, and core API latency. Redact upstream credentials and signed URL query strings at every ingress and logging boundary.

```bash
java -version
node --version
sudo stat -c '%U %G %a %n' /var/lib/kidoo/mirror /var/lib/kidoo/mirror/session.txt
curl --fail --silent --show-error http://127.0.0.1:8080/api/health
sudo systemctl status kidoo.service --no-pager
sudo journalctl -u kidoo.service -n 50 --no-pager
```

Set `MIRROR_RECOVERY_NOTIFICATION_WEBHOOK` to an approved HTTPS receiver to enable intervention alerts. The default is blank. The poll interval is `MIRROR_RECOVERY_NOTIFICATION_POLL=5s`. The payload contains event, incident ID, attempt ID, state and timestamp. Delivery is best effort with no redirects and one dispatch per attempt/event in a running service process. A restart can resend a pending alert. Treat the recovery journal as authoritative and deduplicate at the receiver using the attempt ID and event. The dashboard reports availability from last observed state and does not actively probe upstream.

For rollback, keep ingress admission and authorized provider limits intact. Roll back the artifact and its environment together. If disabling recovery, first remove or replace a revoked credential so legacy file polling cannot load it again. Keep the signing key stable whenever rolling back to a compatible link format. Working provider CDN URLs remain subject to provider validity.

## Mobile handoffs

- [Tower Android implementation prompt](tower-android-playback-claude-prompt.md)
- [MovBugios implementation prompt](movbugios-playback-claude-prompt.md)

## Latest local mock run

The fixture kept one managed upstream slot and a 100 ms minimum start interval. Durations are milliseconds. CPU is average logical cores consumed by the test JVM during the wave; RSS includes the Spring server, client, mock provider and test runtime in that process.

| Wave | Result | Avg / P95 / P99 | Max queue | Immediate rejects | Upstream calls | CPU cores | Peak RSS MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Warm HLS | 200/200 successful | 376 / 683 / 734 | 0 | 0 | 0 | 2.05 | 686.3 |
| Distinct cold HLS | 8/200 successful | 487 / 2,082 / 3,000 | 16 | 176 | 32 | 0.86 | 702.1 |
| Expired cached token | 8/200 successful | 617 / 2,072 / 6,979 | 16 | 176 | 10 | 0.35 | 705.3 |
| Upstream unavailable | 8 returned 502, 192 overloaded | 581 / 2,062 / 6,292 | 16 | 176 | 1 | 0.36 | 709.9 |
| Access challenge | 8 returned 403, 192 overloaded | 600 / 2,066 / 6,237 | 16 | 176 | 1 | 0.28 | 710.5 |
| Upstream throttling | 8 returned 429, 192 overloaded | 582 / 2,070 / 6,322 | 16 | 176 | 1 | 0.28 | 723.7 |
| Confirmed cooldown | 200 returned 429 | 256 / 1,084 / 1,097 | 0 | 0 | 0 | 1.92 | 721.1 |
| Stalled body | 3 returned 502, 197 overloaded | 1,521 / 2,179 / 20,194 | 16 | 177 | 1 | 0.31 | 641.2 |
| Mixed core requests | 288/288 successful | 58 / 201 / 288 | — | — | — | — | — |

The mixed mirror lane reached eight workers and a depth of 16. It recorded 232 immediate foreground rejections, including additional mirror requests issued by the mixed clients, so that counter has a larger denominator than the 200-request stall wave. The unrelated API call while the upstream was stalled completed in 37.5 ms. The warm P95 rose from 592 ms to 683 ms in this run, while the mixed core P95 moved from 491.5 ms to 200.9 ms. These are local observations, not a statistically controlled performance attribution or evidence that the provider grants 200 concurrent streams.

The separate 200-request recovery fixture returned 200 controlled `503` responses during confirmed expiry. Average latency was 332 ms, P95 was 481 ms, and P99 was 499 ms. The mock observed one expiry response, one acquisition process, and one validation request. The authenticated core probe took 20.1 ms and the simulated handoff completed in 729 ms. That duration includes the fixture-controlled handoff and is independent of actual operator verification time.

The original four admin suites failed because their registration fixtures omitted the administrator key when requesting the parent role. The registration endpoint correctly returned a child account. The fixtures now supply the key only for parent registration and assert the issued role, while preserving the existing admin authorization assertions.
