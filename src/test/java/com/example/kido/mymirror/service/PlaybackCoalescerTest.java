package com.example.kido.mymirror.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

import com.example.kido.mymirror.MirrorRequestLimit;
import com.example.kido.mymirror.MirrorSessionProvider;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PlaybackCoalescerTest {

    private static final long BUDGET = 1_000;

    private static final class Owners implements Supplier<CompletableFuture<ResponseEntity<String>>> {
        final AtomicInteger calls = new AtomicInteger();
        final List<CompletableFuture<ResponseEntity<String>>> created = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<ResponseEntity<String>> get() {
            calls.incrementAndGet();
            var fresh = new CompletableFuture<ResponseEntity<String>>();
            created.add(fresh);
            return fresh;
        }
    }

    @Test
    void oneOwnerResolvesForEveryJoinerSharingAKey() {
        var coalescer = new PlaybackCoalescer(true, new SimpleMeterRegistry());
        var owners = new Owners();

        List<DeferredResult<ResponseEntity<String>>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) results.add(coalescer.master("k", BUDGET, owners));

        // Exactly one admission happened; the other four joined it.
        assertThat(owners.calls.get()).isEqualTo(1);
        assertThat(owners.created).hasSize(1);
        results.forEach(r -> assertThat(r.getResult()).isNull());

        owners.created.get(0).complete(ResponseEntity.ok("MASTER"));

        for (var r : results) {
            assertThat(r.getResult()).isInstanceOf(ResponseEntity.class);
            @SuppressWarnings("unchecked")
            var body = ((ResponseEntity<String>) r.getResult()).getBody();
            assertThat(body).isEqualTo("MASTER");
        }
    }

    @Test
    void sharedFailurePropagatesToEveryJoinerUnwrapped() {
        var coalescer = new PlaybackCoalescer(true, new SimpleMeterRegistry());
        var owners = new Owners();

        var a = coalescer.master("k", BUDGET, owners);
        var b = coalescer.master("k", BUDGET, owners);
        assertThat(owners.calls.get()).isEqualTo(1);

        owners.created.get(0).completeExceptionally(new MirrorRequestLimit.Busy());

        assertThat(a.getResult()).isInstanceOf(MirrorRequestLimit.Busy.class);
        assertThat(b.getResult()).isInstanceOf(MirrorRequestLimit.Busy.class);
    }

    @Test
    void entryIsReleasedOnCompletionSoTheNextColdStartReadmits() {
        var coalescer = new PlaybackCoalescer(true, new SimpleMeterRegistry());
        var owners = new Owners();

        coalescer.master("k", BUDGET, owners);
        owners.created.get(0).complete(ResponseEntity.ok("FIRST"));

        // The window has closed; a fresh request for the same key must admit anew, not join the gone entry.
        var second = coalescer.master("k", BUDGET, owners);
        assertThat(owners.calls.get()).isEqualTo(2);
        owners.created.get(1).complete(ResponseEntity.ok("SECOND"));

        @SuppressWarnings("unchecked")
        var body = ((ResponseEntity<String>) second.getResult()).getBody();
        assertThat(body).isEqualTo("SECOND");
    }

    @Test
    void disabledCoalescerAdmitsEveryCallerIndependently() {
        var coalescer = new PlaybackCoalescer(false, new SimpleMeterRegistry());
        var owners = new Owners();

        coalescer.master("k", BUDGET, owners);
        coalescer.master("k", BUDGET, owners);
        coalescer.master("k", BUDGET, owners);

        assertThat(owners.calls.get()).isEqualTo(3);
    }

    @Test
    void differentKeysDoNotShareAnAdmission() {
        var coalescer = new PlaybackCoalescer(true, new SimpleMeterRegistry());
        var owners = new Owners();

        coalescer.master("a", BUDGET, owners);
        coalescer.master("b", BUDGET, owners);

        assertThat(owners.calls.get()).isEqualTo(2);
    }

    @Test
    void synchronousAdmissionFailureInstallsNoEntryAndPropagates() {
        var coalescer = new PlaybackCoalescer(true, new SimpleMeterRegistry());
        AtomicInteger calls = new AtomicInteger();
        // First admission throws synchronously (as checkCooldown() does under an active cooldown); the
        // second succeeds. Busy stands in for any synchronous admission throw — the behaviour under test
        // is that the throw propagates and leaves no entry behind, whatever its type.
        Supplier<CompletableFuture<ResponseEntity<String>>> flaky = () -> {
            if (calls.getAndIncrement() == 0) throw new MirrorRequestLimit.Busy();
            return CompletableFuture.completedFuture(ResponseEntity.ok("OK"));
        };

        assertThatThrownBy(() -> coalescer.master("k", BUDGET, flaky))
                .isInstanceOf(MirrorRequestLimit.Busy.class);

        // No poisoned entry was left behind: the retry admits fresh and succeeds.
        var retry = coalescer.master("k", BUDGET, flaky);
        assertThat(calls.get()).isEqualTo(2);
        @SuppressWarnings("unchecked")
        var body = ((ResponseEntity<String>) retry.getResult()).getBody();
        assertThat(body).isEqualTo("OK");
    }

    @Test
    void asyncFailureReleasesEntrySoNextColdStartReadmits() {
        // Entry-cleanup guarantee on the ASYNC failure path: the owner's shared future completes
        // exceptionally (an upstream error surfaced after admission, not a synchronous throw). whenComplete
        // fires on failure too, so the inFlight entry must be removed and the next cold start must admit anew
        // rather than join a dead, already-failed future.
        var coalescer = new PlaybackCoalescer(true, new SimpleMeterRegistry());
        var owners = new Owners();

        var first = coalescer.master("k", BUDGET, owners);
        owners.created.get(0).completeExceptionally(new MirrorRequestLimit.DeadlineExceeded());
        assertThat(first.getResult()).isInstanceOf(MirrorRequestLimit.DeadlineExceeded.class);

        // The failed entry is gone: a fresh request admits a new owner instead of inheriting the failure.
        var second = coalescer.master("k", BUDGET, owners);
        assertThat(owners.calls.get()).isEqualTo(2);
        owners.created.get(1).complete(ResponseEntity.ok("RECOVERED"));
        @SuppressWarnings("unchecked")
        var body = ((ResponseEntity<String>) second.getResult()).getBody();
        assertThat(body).isEqualTo("RECOVERED");
    }

    @Test
    void sessionReplacedMidResolveRevokesEveryJoinerAndReleasesEntry() {
        // Session-replacement / generation-fencing guarantee at the coalescer seam. When the operator session
        // is replaced while a shared resolution is in flight, the owner's hlsMaster fences via
        // sessions.ifCurrent(...) and throws Revoked. That one fenced failure must reach EVERY joiner (never a
        // stale master served to some), and the entry must be released so the next attempt re-resolves fresh
        // against the new session.
        var coalescer = new PlaybackCoalescer(true, new SimpleMeterRegistry());
        var owners = new Owners();

        var a = coalescer.master("sign\u0000tt0001\u0000cookie", BUDGET, owners);
        var b = coalescer.master("sign\u0000tt0001\u0000cookie", BUDGET, owners);
        var c = coalescer.master("sign\u0000tt0001\u0000cookie", BUDGET, owners);
        assertThat(owners.calls.get()).isEqualTo(1);

        owners.created.get(0).completeExceptionally(new MirrorSessionProvider.Revoked());

        for (var r : List.of(a, b, c)) {
            assertThat(r.getResult()).isInstanceOf(MirrorSessionProvider.Revoked.class);
        }
        // Entry released despite the fence: a re-admit after the session was replaced resolves anew.
        coalescer.master("sign\u0000tt0001\u0000cookie", BUDGET, owners);
        assertThat(owners.calls.get()).isEqualTo(2);
    }

    @Test
    void aDisconnectedWaiterDoesNotCancelTheSharedResolutionForOthers() {
        // Cancellation / client-disconnect guarantee. One waiter going away — its DeferredResult settled by the
        // container on disconnect/expiry — must NOT cancel the shared upstream resolution; the remaining
        // joiners still receive it. The coalescer has no cancellation path, so the shared future is never
        // cancelled by a vanished waiter.
        var coalescer = new PlaybackCoalescer(true, new SimpleMeterRegistry());
        var owners = new Owners();

        var ownerResult = coalescer.master("k", BUDGET, owners);
        var joinerResult = coalescer.master("k", BUDGET, owners);
        assertThat(owners.calls.get()).isEqualTo(1);

        // The owner's client disconnects: the container settles its DeferredResult out from under the resolve.
        ownerResult.setErrorResult(new RuntimeException("client disconnected"));

        // The shared resolution completes regardless, and was never cancelled by the lost waiter...
        owners.created.get(0).complete(ResponseEntity.ok("MASTER"));
        assertThat(owners.created.get(0).isCancelled()).isFalse();

        // ...so the surviving joiner still gets the real result.
        @SuppressWarnings("unchecked")
        var body = ((ResponseEntity<String>) joinerResult.getResult()).getBody();
        assertThat(body).isEqualTo("MASTER");
    }

    @Test
    void authenticatedScopesNeverShareAnAdmission() {
        // Authenticated-caller isolation using controller-shaped keys: sign(cookie) + id + scope. Two different
        // principals, and an unauthenticated cookie caller, for the same session+title must each admit
        // independently — an authenticated play is never served another caller's shared resolution.
        var coalescer = new PlaybackCoalescer(true, new SimpleMeterRegistry());
        var owners = new Owners();

        coalescer.master("sign\u0000tt0001\u0000principal:alice", BUDGET, owners);
        coalescer.master("sign\u0000tt0001\u0000principal:bob", BUDGET, owners);
        coalescer.master("sign\u0000tt0001\u0000cookie", BUDGET, owners);
        assertThat(owners.calls.get()).isEqualTo(3);

        // ...while two requests from the SAME principal for the same title DO collapse onto one admission.
        // A fresh coalescer, because the alice entry above is still in flight (its future never completed) and
        // would otherwise be joined here.
        var sameScope = new PlaybackCoalescer(true, new SimpleMeterRegistry());
        var shared = new Owners();
        sameScope.master("sign\u0000tt0001\u0000principal:alice", BUDGET, shared);
        sameScope.master("sign\u0000tt0001\u0000principal:alice", BUDGET, shared);
        assertThat(shared.calls.get()).isEqualTo(1);
    }
}
