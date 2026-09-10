package com.example.kido.media.together;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.security.GuestPrincipal;

/**
 * The one thing a watch party guest is allowed to do.
 *
 * <p>A guest token authenticates, but it authorises nothing on its own. Every endpoint
 * opened to guests asks here first, and the answer is the same everywhere: this title,
 * while this party is live, and nothing else.
 *
 * <h2>Why the party is re-read every time</h2>
 * The token carries its own expiry, but a party routinely ends hours before that — the
 * film finishes, the host closes the app. Trusting {@code exp} alone would leave a
 * working credential behind after the evening is over, so liveness comes from the
 * database on every request. It is one indexed primary-key lookup against a request
 * that is about to move megabytes.
 */
@Service
public class WatchPartyGrants {

    private final WatchPartyRepository parties;

    public WatchPartyGrants(WatchPartyRepository parties) {
        this.parties = parties;
    }

    /**
     * Refuses a guest who asked for the wrong title or whose party is over.
     *
     * @param guest  the caller when they are a guest, and null when they are not —
     *               {@code @AuthenticationPrincipal} resolves by type, so an account
     *               holder arrives here as null and passes straight through. Their
     *               access was already settled by the security chain.
     * @param itemId the title the request is really for. For a transcode that means
     *               the item behind the session, not the session id: an opaque id is
     *               not a permission.
     */
    public void requirePlayable(GuestPrincipal guest, String itemId) {
        if (guest == null) {
            return;
        }
        if (!guest.mayPlay(itemId)) {
            // Deliberately not a 404. The guest knows the party exists; what they are
            // being told is that this particular link is not for them.
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "This watch party is for a different title");
        }
        if (!parties.existsByIdAndEndedAtIsNull(guest.partyId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "That watch party has ended");
        }
    }
}
