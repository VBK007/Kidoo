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
    public static final String CHAT = "chat";
    public static final String CHAT_HISTORY = "chat-history";

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
     * One thing somebody said, to the party and to nobody else.
     *
     * <p>Deliberately not a persisted entity, and that is the feature rather than a
     * shortcut. A party's chat lives in the registry's in-memory record of the party
     * and is released when the party ends, so "the messages are gone afterwards" is a
     * property of where they are kept rather than a promise some later cleanup job has
     * to honour. Nothing writes them to the database, so nothing has to remember to
     * delete them, and a server restart takes them with it.
     *
     * <p>Ids are per-message so a client can de-duplicate its own optimistic echo
     * against the copy that comes back from the server.
     *
     * @param from display name at the time of sending, not a profile id: the name is
     *             all a reader needs, and the id would outlive the message
     */
    public record ChatMessageDto(String id, String from, String text, long atEpochMs) {}

    /**
     * A single message, fanned out to everyone currently connected.
     *
     * <p>The field is {@code chatMessage} rather than the obvious {@code message}
     * because {@link ErrorFrame} already puts a plain string under that name. A client
     * that reads every frame into one shape — which is the point of every frame
     * carrying a type — would need that field to be a string and an object at once.
     */
    public record ChatFrame(String type, ChatMessageDto chatMessage) {}

    /**
     * What has been said so far, sent to one newcomer as they connect.
     *
     * <p>Joining twenty minutes in and seeing an empty panel would suggest nobody had
     * spoken. Bounded, because this is the backlog of a conversation rather than a
     * transcript of one.
     */
    public record ChatHistoryFrame(String type, List<ChatMessageDto> messages) {}

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
    public record InboundFrame(String type, Double positionSeconds, Boolean buffering,
                               String text) {

        public static final String REPORT = "report";

        public boolean isControl() {
            return PLAY.equals(type) || PAUSE.equals(type) || SEEK.equals(type);
        }
    }
}
