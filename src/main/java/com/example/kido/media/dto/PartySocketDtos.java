package com.example.kido.media.dto;

import java.util.List;

import com.example.kido.media.dto.WatchPartyDtos.ClockDto;
import com.example.kido.media.dto.WatchPartyDtos.MemberDto;
import com.example.kido.media.dto.WatchPartyDtos.PendingGuestDto;

/**
 * What travels over a watch party socket, in both directions.
 *
 * <p>Every frame carries a {@code type} so a client can switch on one field, and the
 * clock frames embed the same {@link ClockDto} the REST fallback returns — a client
 * that cannot hold a socket open polls {@code GET /api/parties/{code}/state} and reads
 * the identical shape out of it, only later.
 */
public final class PartySocketDtos {

    private PartySocketDtos() {}

    /** Server frame types, as they appear on the wire. */
    public static final String TICK = "tick";
    public static final String PLAY = "play";
    public static final String PAUSE = "pause";
    public static final String SEEK = "seek";
    public static final String MEMBERS = "members";
    public static final String PENDING = "pending";
    public static final String ENDED = "ended";
    public static final String ERROR = "error";

    /**
     * Where the film is.
     *
     * <p>Sent on a timer as {@link #TICK}, and immediately as {@link #PLAY},
     * {@link #PAUSE} or {@link #SEEK} when the host does something. The distinction is
     * only for the client's benefit — the clock payload is identical, but "Amma paused"
     * is worth showing and a routine tick is not.
     *
     * @param by display name of whoever caused it, or null for a timer tick
     */
    public record ClockFrame(String type, ClockDto clock, String by) {}

    /** The member list, resent whenever someone connects, drops out or is admitted. */
    public record MembersFrame(String type, List<MemberDto> members) {}

    /**
     * Someone is at the door.
     *
     * <p>Sent to the host alone, and only the host: the payload is a name a stranger
     * typed, and nobody else in the party can act on it. It carries one request rather
     * than the whole queue because it is a prompt, not a list — the full pending set is
     * on {@code PartyDto} for a host reopening the screen.
     */
    public record PendingFrame(String type, PendingGuestDto guest) {}

    /** Final frame before the server closes the socket. */
    public record EndedFrame(String type, String reason) {}

    /**
     * Something the client sent was refused.
     *
     * <p>Sent rather than closing the socket, even for a member who tried to drive the
     * playhead: a stray control is a client bug worth surfacing, and dropping the
     * connection would hide it behind a reconnect.
     */
    public record ErrorFrame(String type, String message) {}

    /**
     * What a client may send.
     *
     * <p>{@code play}, {@code pause} and {@code seek} are host-only and set the shared
     * clock. {@code report} is what everyone sends every few seconds: from the host it
     * re-anchors the clock to their real position, and from a member it only records
     * how far off they are, so the host can see who is struggling.
     *
     * @param positionSeconds required for every type except a bare {@code report} from
     *                        a member that is only updating {@code buffering}
     */
    public record InboundFrame(String type, Double positionSeconds, Boolean buffering) {

        public static final String REPORT = "report";

        public boolean isControl() {
            return PLAY.equals(type) || PAUSE.equals(type) || SEEK.equals(type);
        }
    }
}
