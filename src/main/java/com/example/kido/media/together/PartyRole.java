package com.example.kido.media.together;

/**
 * How much authority someone has over a party's playhead.
 *
 * <p>Only the host may play, pause or seek. Shared control sounds friendlier but two
 * people scrubbing at once produces a feedback loop — each correction is itself a seek
 * the others must follow — and there is no tie-break that does not eventually land on
 * "one of them wins", which is this enum.
 */
public enum PartyRole {

    /** Created the party. The only role whose player drives everyone else's. */
    HOST,

    /** Joined with an account on this server. Has a profile, so playback is recorded. */
    MEMBER,

    /**
     * Joined with a code alone and no account. Watches the party's title and nothing
     * else: with no {@code AppUser} behind the token there is no profile to attribute
     * progress to, and no library to browse.
     */
    GUEST
}
