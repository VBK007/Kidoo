package com.example.kido.media.engagement.mirror;

import org.springframework.http.HttpStatus;

import com.example.kido.common.ApiException;

/**
 * The one place a mirror id is let in.
 *
 * <p>A mirror id is an opaque external catalogue token — a Netflix/Hotstar numeric
 * id, a Prime ASIN — that never names a row in this server's own {@code media_items}
 * table. That is the whole reason the mirror engagement path exists apart from the
 * catalogue one: there is no item to look up, so the id itself is all the validation
 * there is. It is trimmed, required to be non-blank, and bounded so a caller cannot
 * grow the like or comment tables a key column at a time by posting arbitrarily long
 * ids. The real mirror ids are short tokens, so the bound is generous and never bites
 * a genuine one.
 */
final class MirrorIds {

    /** Comfortably past any real catalogue id; a defensive cap, not a format rule. */
    static final int MAX_LENGTH = 128;

    private MirrorIds() {}

    /** The trimmed id, or a 400 when it is blank or implausibly long. */
    static String require(String id) {
        String trimmed = id == null ? "" : id.trim();
        if (trimmed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A mirror id is required");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That mirror id is not valid");
        }
        return trimmed;
    }
}
