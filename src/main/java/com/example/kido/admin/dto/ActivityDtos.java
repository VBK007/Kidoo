package com.example.kido.admin.dto;

import java.time.Instant;

/**
 * Read models for two questions the aggregate dashboard can't answer: who just
 * joined, and who's on the app right now and looking at what.
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
