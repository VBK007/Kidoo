# Mirror resilience and rollout

The mirror bulkhead limits work admitted by Kidoo. HTTP request rate, in-flight HTTP requests, and simultaneous streams are separate provider limits. Obtain written authorization for the deployment, including permitted credentials, origins, request rates, and stream counts. Keep the mirror feature out of production until those limits and the recovery process are documented. The defaults below describe local behavior.

| Environment setting | Default | Purpose |
| --- | --- | --- |
| `MIRROR_WORKERS` | 8 | Concurrent foreground handlers |
| `MIRROR_QUEUE_CAPACITY` | 16 | Pending foreground handlers |
| `MIRROR_QUEUE_TIMEOUT` | 2s | Maximum admission wait before overload handling |
| `MIRROR_REQUEST_TIMEOUT` | 30s | Application request deadline, including queue time |
| `MIRROR_MAX_CONCURRENT` | 1 | Managed outbound requests in flight per process |
| `MIRROR_MIN_INTERVAL_MS` | 100 | Minimum time between managed outbound starts |
| `MIRROR_TOKEN_FAILURE_BACKOFF` | 3s | Reuse a transient verification failure briefly |
| `MIRROR_REVOCATION_STATUS` | 0 | Disable status-only revocation until the provider documents a definitive 4xx signal |
| `MIRROR_SESSION_FILE` | existing tool directory session file | Operator-provisioned credential file |
| `MIRROR_SESSION_RELOAD_INTERVAL` | 30s | Poll for an atomic credential replacement |
| `MIRROR_VARIANT_SIGNING_KEY` | empty | Base64 key with at least 32 decoded random bytes. Empty uses a process-local random key |
| `MIRROR_VARIANT_VERIFICATION_KEYS` | empty | Comma-separated verification keys for rotation |
| `MIRROR_VARIANT_LINK_LIFETIME` | 15m | Local lifetime of newly issued variant capabilities |

The optional Home lane has two workers and four queued requests. Its catalogue work uses four workers and a 32-task queue. Home can serve its existing core payload when the optional work cannot complete.

## Client contract

Foreground overload returns `503` with `Retry-After: 2`. Upstream HTTP `429` retains its valid `Retry-After`. Application deadline exhaustion can return `504`. Confirmed revocation returns `503` with an additive JSON `code` of `MIRROR_SESSION_REVOKED`. Android and iOS can keep their existing status handling until they explicitly support the new code. Once supported, this code should stop automatic retries and surface a source-unavailable state. On `403`, follow the provider-approved access flow. On transient overload, wait for `Retry-After`, add jitter, and retry only if the playback attempt is still active.

The Home response body is unchanged. `X-Mirror-Catalogue-State` distinguishes `available`, `empty`, `missing`, and `unavailable`. Segments stay on the provider CDN. Variant links are opaque bearer capabilities, so clients should avoid logging them and reload the authorized master when a link expires. Kidoo still needs player-side cancellation and release on title switches.

## Deploy and rotate

1. Keep the existing Ubuntu service, Firebase dynamic endpoint discovery, and public routes. Set deployment secrets in the existing protected environment or secret store. Set approved origins and documented quotas before enabling the source.
2. Atomically provision the authorized session file with restricted permissions. A confirmed revoked credential needs replacement. The current revocation state is in memory, so remove or replace a revoked file before restarting a process.
3. Set the same signing key on every serving replica. To rotate, first deploy the next key as verification-only everywhere. After that deployment converges, make it the active signing key and keep the previous key in the verification list for at least the maximum link lifetime after the last old issuance. Then remove the previous key. Roll out new-format signing on a single mirror worker or drain the old version first so active links are served by a compatible build.
4. Canary with local fixtures, then monitor queue depth, rejections, core latency, cooldown, session state, and upstream latency. Existing HTTP metrics carry response status. Use a protected metrics exporter and redact query strings at proxies and access logs.
5. Roll back the application artifact and its settings together. The previous variant wire format differs, so clients may need to reload playback. Keep the provider quotas and the authorized credential file in place. Disable mirror traffic at the ingress if rollback cannot maintain the isolation or authorization requirements.

## Multiple replicas

Provider HTTP admission, cooldown, session generation, revocation, and caches are process-local. Use one active mirror-serving instance until enforcement is shared. Multiple core Kidoo replicas can keep their existing discovery and routing while forwarding the mirror surface through that single owner. A multi-writer design needs atomic quota reservation and a shared cooldown and session generation. An existing transactional database or an internal quota service may be sufficient after measuring contention and clock behavior. Redis is not a prerequisite. Only enforce or count active playback sessions using the provider's documented entitlement and lifecycle.

## Local 200-request comparison

The local fixture used one managed upstream slot and a 100 ms minimum interval. It exercised Spring MVC with embedded Tomcat and a loopback mock. Discovery was fixed to the mock. These numbers apply to this fixture and do not measure sustained video delivery.

| Scenario | Before | After |
| --- | --- | --- |
| Warm, same authorized key | 200/200 successful, P95 536 ms | 200/200 successful, P95 592 ms, zero upstream calls and zero mirror workers |
| Distinct cold starts | 20/200 successful, P95 7,461 ms | 8/200 successful, P95 2,123 ms, 192 overload responses |
| Stalled upstream response body | P95 20,916 ms | P95 2,179 ms, maximum mirror queue depth 16 and 8 mirror workers |
| Unrelated core probe during stall | 19.87 s | 116.5 ms |
| Sustained local mixed fixture | No equivalent baseline | 288 core requests, no failures, average 101.2 ms, P95 491.5 ms, P99 536.7 ms |

The stall probe used different core endpoints in the two versions, so it illustrates the isolation change without providing an endpoint latency comparison. An abandoned client request was cancelled in the fixture and the pending queue drained by its configured expiry. Immediate disconnect detection depends on the servlet container. The load test did not use a live provider.
