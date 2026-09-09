package com.example.kido.media.matchfix;

import java.util.List;

import com.example.kido.media.catalog.MediaItem;

/**
 * Supplies possible correct identities for a wrongly-matched item.
 *
 * <p>An interface for the same reason {@code PurchaseVerifier} is one: the useful
 * implementation depends on what the deployment has available. This library is
 * configured for local sidecar metadata with no online lookup, so
 * {@link SidecarCandidateProvider} works from what is on disk. A provider backed by an
 * online database would slot in beside it without the fix screen changing, which is why
 * candidates carry a {@code source} label — the client can say where a suggestion came
 * from rather than presenting a guess and a scraped record as equally authoritative.
 */
public interface MetadataCandidateProvider {

    /**
     * @param item  the item being corrected
     * @param query what the owner typed, or null to suggest from the file itself
     * @param limit maximum candidates to return
     */
    List<MatchCandidate> candidatesFor(MediaItem item, String query, int limit);

    /** Short label for the client, e.g. {@code disk}. */
    String sourceName();
}
