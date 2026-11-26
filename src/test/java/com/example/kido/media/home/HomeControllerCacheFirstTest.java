package com.example.kido.media.home;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

import com.example.kido.media.dto.HomeDtos.HomeDto;
import com.example.kido.media.dto.HomeDtos.MirrorRailDto;
import com.example.kido.media.dto.HomeDtos.MirrorTileDto;
import com.example.kido.mymirror.MirrorBulkhead;
import com.example.kido.mymirror.catalogue.MirrorCatalogueProperties;
import com.example.kido.mymirror.service.MirrorHomeService;
import com.example.kido.mymirror.service.MirrorHomeService.RailResult;

/**
 * Deterministic unit coverage for {@link HomeController}'s cache-first wiring — the branches that
 * {@code MirrorHomeEndpointIntegrationTest} can only exercise by racing a real executor.
 *
 * <p>The bulkhead is mocked so {@code submitOptional(action, budget, fallback)} returns the
 * <b>fallback</b> argument directly (as a resolved {@link DeferredResult}), which is exactly what a
 * saturated executor delivers. That lets us assert, with no timing, that the controller wires the
 * right saturation fallback for each warm state:
 * <ul>
 *   <li>a <b>fresh</b> warm catalogue never touches the bulkhead at all;</li>
 *   <li>a <b>stale/partial</b> warm catalogue is handed to the bulkhead as the fallback, carrying the
 *       cached rails and an honest {@code X-Mirror-Catalogue-State} header;</li>
 *   <li>with the flag <b>off</b>, the fallback is the empty mirror flagged {@code unavailable}.</li>
 * </ul>
 */
class HomeControllerCacheFirstTest {

    private static final MirrorRailDto CACHED_RAIL = new MirrorRailDto("mirror", "From the mirror",
            List.of(new MirrorTileDto("111", "Alpha", "https://imgcdn.kim/nf/v/111.jpg", null)));

    private static final HomeDto BASE_HOME =
            new HomeDto(List.of(), List.of(), null, null, "2026-11-26T00:00:00Z", null);

    private HomeService service() {
        HomeService service = mock(HomeService.class);
        when(service.home(any(), any(), any(), anyInt())).thenReturn(BASE_HOME);
        return service;
    }

    /**
     * A mocked bulkhead whose {@code submitOptional} resolves to its fallback argument — the exact
     * behaviour of a saturated executor, made deterministic.
     */
    @SuppressWarnings("unchecked")
    private MirrorBulkhead saturatedBulkhead() {
        MirrorBulkhead bulkhead = mock(MirrorBulkhead.class);
        when(bulkhead.submitOptional(any(), any(Duration.class), any())).thenAnswer(inv -> {
            ResponseEntity<HomeDto> fallback = (ResponseEntity<HomeDto>) inv.getArgument(2);
            var result = new DeferredResult<ResponseEntity<HomeDto>>();
            result.setResult(fallback);
            return result;
        });
        return bulkhead;
    }

    private static ResponseEntity<HomeDto> resolve(DeferredResult<ResponseEntity<HomeDto>> deferred) {
        Object body = deferred.getResult();
        assertNotNull(body, "DeferredResult was not resolved");
        @SuppressWarnings("unchecked")
        ResponseEntity<HomeDto> entity = (ResponseEntity<HomeDto>) body;
        return entity;
    }

    @Test
    void aFreshWarmCatalogueIsServedWithoutTouchingTheBulkhead() {
        MirrorHomeService mirrorHome = mock(MirrorHomeService.class);
        MirrorBulkhead bulkhead = saturatedBulkhead();
        MirrorCatalogueProperties props = new MirrorCatalogueProperties();
        props.getCacheFirst().setEnabled(true);
        when(mirrorHome.warmRails("sess=1", "nf")).thenReturn(new RailResult(List.of(CACHED_RAIL), "cached"));

        HomeController controller = new HomeController(service(), mirrorHome, bulkhead, props);
        var deferred = controller.home(null, null, null, 20, "sess=1", "nf");

        ResponseEntity<HomeDto> response = resolve(deferred);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("cached", response.getHeaders().getFirst("X-Mirror-Catalogue-State"));
        assertEquals(List.of(CACHED_RAIL), response.getBody().mirror());
        // The whole cache-first point: a fresh hit submits NO work to the bulkhead, and the live
        // upstream read is never consulted.
        verify(bulkhead, never()).submitOptional(any(), any(Duration.class), any());
        verify(mirrorHome, never()).railWithStatus(any(), any());
    }

    @Test
    void aStaleWarmCatalogueIsWiredAsTheSaturationFallback() {
        MirrorHomeService mirrorHome = mock(MirrorHomeService.class);
        MirrorBulkhead bulkhead = saturatedBulkhead();
        MirrorCatalogueProperties props = new MirrorCatalogueProperties();
        props.getCacheFirst().setEnabled(true);
        when(mirrorHome.warmRails("sess=1", "pv")).thenReturn(new RailResult(List.of(CACHED_RAIL), "stale"));

        HomeController controller = new HomeController(service(), mirrorHome, bulkhead, props);
        var deferred = controller.home(null, null, null, 20, "sess=1", "pv");

        // The executor is saturated (our mock returns the fallback), so the controller must serve the
        // stale cached rails — not an empty mirror — and report the state honestly.
        ResponseEntity<HomeDto> response = resolve(deferred);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("stale", response.getHeaders().getFirst("X-Mirror-Catalogue-State"));
        assertEquals(List.of(CACHED_RAIL), response.getBody().mirror());
        // It still SUBMITTED a live refresh (the bounded mechanism); the stale body was only the fallback.
        verify(bulkhead).submitOptional(any(), eq(Duration.ofSeconds(8)), any());
    }

    @Test
    void aPartialWarmCatalogueIsWiredAsTheSaturationFallback() {
        MirrorHomeService mirrorHome = mock(MirrorHomeService.class);
        MirrorBulkhead bulkhead = saturatedBulkhead();
        MirrorCatalogueProperties props = new MirrorCatalogueProperties();
        props.getCacheFirst().setEnabled(true);
        when(mirrorHome.warmRails("sess=1", "nf")).thenReturn(new RailResult(List.of(CACHED_RAIL), "partial"));

        HomeController controller = new HomeController(service(), mirrorHome, bulkhead, props);
        var deferred = controller.home(null, null, null, 20, "sess=1", "nf");

        ResponseEntity<HomeDto> response = resolve(deferred);
        assertEquals("partial", response.getHeaders().getFirst("X-Mirror-Catalogue-State"));
        assertEquals(List.of(CACHED_RAIL), response.getBody().mirror());
    }

    @Test
    void aColdCacheSubmitsTheLiveRefreshWithAnUnavailableFallback() {
        MirrorHomeService mirrorHome = mock(MirrorHomeService.class);
        MirrorBulkhead bulkhead = saturatedBulkhead();
        MirrorCatalogueProperties props = new MirrorCatalogueProperties();
        props.getCacheFirst().setEnabled(true);
        // Nothing warm on hand: the fallback carries no rails and is flagged unavailable, but a live
        // refresh is still submitted through the bounded bulkhead.
        when(mirrorHome.warmRails("sess=1", "nf")).thenReturn(new RailResult(List.of(), "unavailable"));

        HomeController controller = new HomeController(service(), mirrorHome, bulkhead, props);
        var deferred = controller.home(null, null, null, 20, "sess=1", "nf");

        ResponseEntity<HomeDto> response = resolve(deferred);
        assertEquals("unavailable", response.getHeaders().getFirst("X-Mirror-Catalogue-State"));
        // Empty warm rails fall back to the base home (its mirror stays null), never an empty mirror list.
        assertEquals(null, response.getBody().mirror());
        verify(bulkhead).submitOptional(any(), eq(Duration.ofSeconds(8)), any());
    }

    @Test
    void withTheFlagOffTheFallbackIsTheEmptyMirrorAndTheWarmReadIsNeverTaken() {
        MirrorHomeService mirrorHome = mock(MirrorHomeService.class);
        MirrorBulkhead bulkhead = saturatedBulkhead();
        MirrorCatalogueProperties props = new MirrorCatalogueProperties(); // cache-first OFF by default
        assertFalse(props.getCacheFirst().isEnabled());

        HomeController controller = new HomeController(service(), mirrorHome, bulkhead, props);
        var deferred = controller.home(null, null, null, 20, "sess=1", "nf");

        ResponseEntity<HomeDto> response = resolve(deferred);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("unavailable", response.getHeaders().getFirst("X-Mirror-Catalogue-State"));
        assertSame(BASE_HOME, response.getBody());
        // Flag off: the cache-only warm read is never consulted, preserving the historical path exactly.
        verify(mirrorHome, never()).warmRails(any(), any());
        // But the live refresh is still submitted, just as before this feature existed.
        verify(bulkhead).submitOptional(any(), eq(Duration.ofSeconds(8)), any());
    }

    // ---- admitted-but-hung refresh: the #1 catalogue-specific deadline correction ----
    //
    // When the mirror executor ADMITS the live refresh but it hangs past the servlet deadline, the
    // bulkhead's own DeferredResult.onTimeout would set a DeadlineExceeded error (a 504), discarding
    // the usable cached catalogue that was handed in as the saturation fallback. The controller
    // installs a catalogue-specific onTimeout on that one DeferredResult to serve the cached catalogue
    // instead. These tests drive that handler deterministically, with no real executor and no timing.

    /**
     * A {@link DeferredResult} that records the {@code onTimeout} handler installed on it, so a test
     * can fire the servlet deadline on demand — exactly what an admitted-but-hung refresh triggers.
     * With a mocked bulkhead, the <i>only</i> handler ever installed is the controller's catalogue
     * override, so {@link #hasTimeoutHandler()} tells whether the controller chose to preserve a
     * cached catalogue on the deadline.
     */
    private static final class CapturingDeferredResult<T> extends DeferredResult<T> {
        private Runnable onTimeout;

        @Override
        public void onTimeout(Runnable callback) {
            this.onTimeout = callback;
            super.onTimeout(callback);
        }

        boolean hasTimeoutHandler() {
            return onTimeout != null;
        }

        void fireTimeout() {
            assertNotNull(onTimeout, "no onTimeout handler was installed");
            onTimeout.run();
        }
    }

    /** A bulkhead that ADMITS the refresh and returns an unresolved result (the admitted work hangs). */
    private MirrorBulkhead hungBulkhead(CapturingDeferredResult<ResponseEntity<HomeDto>> hung) {
        MirrorBulkhead bulkhead = mock(MirrorBulkhead.class);
        when(bulkhead.submitOptional(any(), any(Duration.class), any())).thenAnswer(inv -> hung);
        return bulkhead;
    }

    @Test
    void anAdmittedButHungRefreshServesTheStaleCatalogueOnDeadlineNotAnError() {
        MirrorHomeService mirrorHome = mock(MirrorHomeService.class);
        MirrorCatalogueProperties props = new MirrorCatalogueProperties();
        props.getCacheFirst().setEnabled(true);
        when(mirrorHome.warmRails("sess=1", "nf")).thenReturn(new RailResult(List.of(CACHED_RAIL), "stale"));

        var hung = new CapturingDeferredResult<ResponseEntity<HomeDto>>();
        HomeController controller = new HomeController(service(), mirrorHome, hungBulkhead(hung), props);

        var deferred = controller.home(null, null, null, 20, "sess=1", "nf");
        assertSame(hung, deferred);
        // The admitted refresh is still running: nothing is resolved before the deadline.
        assertFalse(hung.hasResult());
        // The servlet deadline fires. The bulkhead's default would be a DeadlineExceeded error; the
        // controller's catalogue-specific handler instead serves the usable stale catalogue, honestly
        // labelled — the exact #1 guarantee: an authorized refresh that times out preserves the cache.
        hung.fireTimeout();
        ResponseEntity<HomeDto> response = resolve(hung);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("stale", response.getHeaders().getFirst("X-Mirror-Catalogue-State"));
        assertEquals(List.of(CACHED_RAIL), response.getBody().mirror());
    }

    @Test
    void anAdmittedButHungRefreshServesAPartialCatalogueOnDeadline() {
        MirrorHomeService mirrorHome = mock(MirrorHomeService.class);
        MirrorCatalogueProperties props = new MirrorCatalogueProperties();
        props.getCacheFirst().setEnabled(true);
        when(mirrorHome.warmRails("sess=1", "nf")).thenReturn(new RailResult(List.of(CACHED_RAIL), "partial"));

        var hung = new CapturingDeferredResult<ResponseEntity<HomeDto>>();
        HomeController controller = new HomeController(service(), mirrorHome, hungBulkhead(hung), props);
        controller.home(null, null, null, 20, "sess=1", "nf");

        hung.fireTimeout();
        ResponseEntity<HomeDto> response = resolve(hung);
        assertEquals("partial", response.getHeaders().getFirst("X-Mirror-Catalogue-State"));
        assertEquals(List.of(CACHED_RAIL), response.getBody().mirror());
    }

    @Test
    void aColdCacheKeepsTheHistoricalDeadlineBehaviourWithNoCatalogueOverride() {
        MirrorHomeService mirrorHome = mock(MirrorHomeService.class);
        MirrorCatalogueProperties props = new MirrorCatalogueProperties();
        props.getCacheFirst().setEnabled(true);
        // No usable cached catalogue on hand.
        when(mirrorHome.warmRails("sess=1", "nf")).thenReturn(new RailResult(List.of(), "unavailable"));

        var hung = new CapturingDeferredResult<ResponseEntity<HomeDto>>();
        HomeController controller = new HomeController(service(), mirrorHome, hungBulkhead(hung), props);
        controller.home(null, null, null, 20, "sess=1", "nf");

        // Nothing cached to preserve: the controller must NOT install a catalogue onTimeout override,
        // leaving the bulkhead's historical deadline handling (a DeadlineExceeded error) exactly intact.
        assertFalse(hung.hasTimeoutHandler());
    }

    @Test
    void withTheFlagOffNoCatalogueDeadlineOverrideIsInstalled() {
        MirrorHomeService mirrorHome = mock(MirrorHomeService.class);
        MirrorCatalogueProperties props = new MirrorCatalogueProperties(); // cache-first OFF by default
        var hung = new CapturingDeferredResult<ResponseEntity<HomeDto>>();
        HomeController controller = new HomeController(service(), mirrorHome, hungBulkhead(hung), props);
        controller.home(null, null, null, 20, "sess=1", "nf");

        // Flag off: the warm read is never taken and no catalogue deadline override is installed, so the
        // pre-feature deadline behaviour is preserved byte-for-byte.
        assertFalse(hung.hasTimeoutHandler());
        verify(mirrorHome, never()).warmRails(any(), any());
    }

    @Test
    void aLateRefreshCompletionCannotOverwriteTheCatalogueAlreadyServedOnDeadline() {
        MirrorHomeService mirrorHome = mock(MirrorHomeService.class);
        MirrorCatalogueProperties props = new MirrorCatalogueProperties();
        props.getCacheFirst().setEnabled(true);
        when(mirrorHome.warmRails("sess=1", "nf")).thenReturn(new RailResult(List.of(CACHED_RAIL), "stale"));

        var hung = new CapturingDeferredResult<ResponseEntity<HomeDto>>();
        HomeController controller = new HomeController(service(), mirrorHome, hungBulkhead(hung), props);
        controller.home(null, null, null, 20, "sess=1", "nf");

        hung.fireTimeout(); // the stale catalogue is served on the deadline
        // The hung refresh finally lands AFTER the deadline. DeferredResult is last-wins, so the late
        // completion is rejected and the catalogue the client already received stands — a late result
        // can never overwrite the served catalogue. (The bulkhead's own isSetOrExpired guard is the
        // same backstop one layer down.)
        boolean accepted = hung.setResult(ResponseEntity.ok()
                .header("X-Mirror-Catalogue-State", "cached")
                .body(BASE_HOME.withMirror(List.of(CACHED_RAIL))));
        assertFalse(accepted, "a late refresh completion must not overwrite the served catalogue");
        assertEquals("stale", resolve(hung).getHeaders().getFirst("X-Mirror-Catalogue-State"));
    }

    @Test
    void aFreshButEmptyWarmCatalogueFallsThroughToTheLiveRefresh() {
        // Edge: state is "cached" but there are no rails — not a usable fresh hit, so the controller
        // must NOT short-circuit; it submits the live refresh like a cold cache.
        MirrorHomeService mirrorHome = mock(MirrorHomeService.class);
        MirrorBulkhead bulkhead = saturatedBulkhead();
        MirrorCatalogueProperties props = new MirrorCatalogueProperties();
        props.getCacheFirst().setEnabled(true);
        when(mirrorHome.warmRails("sess=1", "nf")).thenReturn(new RailResult(List.of(), "cached"));

        HomeController controller = new HomeController(service(), mirrorHome, bulkhead, props);
        var deferred = controller.home(null, null, null, 20, "sess=1", "nf");

        ResponseEntity<HomeDto> response = resolve(deferred);
        // Falls through to the bulkhead; fallback header is the warm state ("cached") but the body is
        // the empty-mirror base home, and a live refresh was submitted.
        assertTrue(response.getStatusCode().is2xxSuccessful());
        verify(bulkhead).submitOptional(any(), eq(Duration.ofSeconds(8)), any());
    }
}
