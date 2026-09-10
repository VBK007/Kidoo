package com.example.kido.media.dto;

import java.time.Instant;
import java.util.List;

import com.example.kido.media.together.MemberState;
import com.example.kido.media.together.PartyClockState;
import com.example.kido.media.together.PartyRole;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response shapes for watching one title together on several devices. */
public final class WatchPartyDtos {

    private WatchPartyDtos() {}

    /** Opens a party around a title the host is about to play. */
    public record CreatePartyRequest(
            @NotBlank String mediaItemId,

            /**
             * Seat count including the host. Null takes the server default.
             *
             * <p>Bounded low deliberately: every seat is an independent stream off one
             * machine, so the honest ceiling is set by upstream bandwidth rather than
             * by how many friends someone has.
             */
            @Min(2) @Max(8) Integer maxMembers,

            /** Whether anonymous guests need the host to tap accept. Null means yes. */
            Boolean requireApprovalForGuests) {
    }

    /**
     * A guest asking to be let in with nothing but the code.
     *
     * @param name what the host sees in the accept prompt — untrusted, so it is length
     *             capped and stripped of anything that is not printable before it is
     *             stored or shown
     */
    public record GuestJoinRequest(@NotBlank @Size(max = 40) String name) {}

    /** The host's answer to one pending guest. */
    public record AdmitRequest(@NotBlank String requestId, boolean allow) {}

    /**
     * What a pending guest gets back, on the first ask and on every poll after it.
     *
     * @param status     where the request stands
     * @param requestId  identifies the row to the host and to subsequent polls
     * @param pollToken  the secret proving this is the device that asked; returned once,
     *                   on the first response, and never again
     * @param token      the guest JWT — null until the host says yes, which is the whole
     *                   point of the pending state
     * @param expiresAt  when the token stops working, whichever comes first between the
     *                   token's own expiry and the party ending
     */
    public record GuestJoinDto(
            MemberState status,
            String requestId,
            String pollToken,
            String token,
            Instant expiresAt) {

        public static GuestJoinDto pending(String requestId, String pollToken) {
            return new GuestJoinDto(MemberState.PENDING, requestId, pollToken, null, null);
        }

        /** A poll that found the host still deciding. The secret is not resent. */
        public static GuestJoinDto stillPending(String requestId) {
            return new GuestJoinDto(MemberState.PENDING, requestId, null, null, null);
        }

        public static GuestJoinDto admitted(String requestId, String token, Instant expiresAt) {
            return new GuestJoinDto(MemberState.ADMITTED, requestId, null, token, expiresAt);
        }

        public static GuestJoinDto denied(String requestId) {
            return new GuestJoinDto(MemberState.DENIED, requestId, null, null, null);
        }
    }

    /**
     * The party's shared playhead at one instant.
     *
     * <p>Both timestamps come from the server's clock, never the host's. A client works
     * out where it should be as
     * {@code positionSeconds + (now + offset - atEpochMillis) / 1000}, where
     * {@code offset} is its running estimate of {@code serverNowEpochMillis - now}.
     * Carrying the server's own "now" in every frame is what makes that estimate
     * possible without a separate time-sync round trip, and what keeps a phone with a
     * badly set clock from dragging the party off.
     */
    public record ClockDto(
            PartyClockState state,
            double positionSeconds,
            long atEpochMillis,
            long serverNowEpochMillis) {
    }

    /**
     * One admitted person, as the member list shows them.
     *
     * @param online       whether they currently hold a socket. Distinct from being
     *                     admitted: someone in a tunnel keeps their seat and their
     *                     place in the list, they just stop receiving the clock.
     * @param buffering    reported by that member's own player; the party clock ignores
     *                     it, but the host may want to pause when someone stalls
     * @param driftSeconds how far off the shared clock they last reported being —
     *                     positive means ahead. Null until they have reported once.
     */
    public record MemberDto(
            String id,
            String name,
            PartyRole role,
            boolean host,
            Instant joinedAt,
            boolean online,
            boolean buffering,
            Double driftSeconds) {
    }

    /** A guest waiting on the host's decision. Sent only to the host. */
    public record PendingGuestDto(String requestId, String name, Instant requestedAt) {}

    /**
     * The whole party as one device sees it.
     *
     * @param youAreHost      whether the caller may drive the playhead, so the client
     *                        does not have to compare ids to decide which controls to
     *                        render
     * @param pending         empty for everyone except the host
     * @param clock           null before the host's player has reported a position
     * @param capacityWarning set when the title will not direct play, i.e. every seat
     *                        costs an ffmpeg process. Advisory: the party is created
     *                        either way, because the host is the one who knows what
     *                        their machine can take.
     */
    public record PartyDto(
            String partyId,
            String code,
            String mediaItemId,
            String itemTitle,
            boolean live,
            int maxMembers,
            boolean youAreHost,
            List<MemberDto> members,
            List<PendingGuestDto> pending,
            ClockDto clock,
            String capacityWarning) {
    }
}
