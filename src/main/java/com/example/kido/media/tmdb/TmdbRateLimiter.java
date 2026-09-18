package com.example.kido.media.tmdb;

import org.springframework.stereotype.Component;

import com.example.kido.media.MediaProperties;

/**
 * A single self-throttle shared by every TMDB caller ({@code CastPhotoService},
 * {@code TmdbMovieService}), so a library scan's cast-photo prefetch and plot lookups
 * don't compound into a burst neither would produce alone.
 *
 * <p>Deliberately not per-caller: two callers each individually well-behaved can still
 * hammer the same upstream API together, and the thing being protected — TMDB's
 * tolerance for this server's traffic — is one shared resource, not two.
 */
@Component
public class TmdbRateLimiter {

    private final MediaProperties props;
    private long lastCallAt = 0;

    public TmdbRateLimiter(MediaProperties props) {
        this.props = props;
    }

    /** Blocks the calling thread just long enough to keep calls spaced apart. */
    public synchronized void throttle() {
        long minIntervalMs = Math.max(0, props.getCastPhotos().getMinRequestIntervalMs());
        long wait = lastCallAt + minIntervalMs - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        lastCallAt = System.currentTimeMillis();
    }
}
