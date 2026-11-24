package com.example.kido.admin.dto;

import java.time.Instant;

/**
 * Read models for the questions the aggregate dashboard can't answer: who just
 * joined, who's on the app right now and looking at what, and what each account has
 * recently played.
 */
public final class ActivityDtos {

    private ActivityDtos() {}

    /** One account's most recent screen — the admin "who's where, right now" feed. */
    public record LiveScreenDto(
            String userId,
            String accountLabel,
            String profileId,
            String screen,
            String itemId,
            String itemTitle,
            String platform,
            Instant at) {}

    /**
     * One title a profile recently played — the admin "recently played" audit.
     *
     * <p>Distinct from {@link LiveScreenDto}, which is where an account is <em>now</em>:
     * this is a durable history of what was actually watched, carried far enough back
     * to audit, and names the profile as well as the account because within one
     * household the question is usually "which of them watched this".
     *
     * <p>{@code percentComplete} is null when the file's duration was never known, the
     * same honest gap {@link com.example.kido.media.playback.PlaybackProgress} leaves;
     * {@code watched} is the finished flag, so a row can be 100%-and-done or stopped
     * part way.
     */
    public record RecentPlayDto(
            String userId,
            String accountLabel,
            String profileId,
            String profileName,
            String itemId,
            String itemTitle,
            Integer percentComplete,
            boolean watched,
            Instant at) {}

    /** One newly created account — what "+1 today" on the growth tile actually is. */
    public record RecentSignupDto(
            String id,
            String username,
            String email,
            String displayName,
            String role,
            Instant createdAt,
            String platform,
            Instant lastSeenAt) {}
}
