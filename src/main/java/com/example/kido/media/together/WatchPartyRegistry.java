package com.example.kido.media.together;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import com.example.kido.media.dto.PartySocketDtos;
import com.example.kido.media.dto.PartySocketDtos.ClockFrame;
import com.example.kido.media.dto.PartySocketDtos.EndedFrame;
import com.example.kido.media.dto.PartySocketDtos.ErrorFrame;
import com.example.kido.media.dto.PartySocketDtos.MembersFrame;
import com.example.kido.media.dto.PartySocketDtos.PendingFrame;
import com.example.kido.media.dto.WatchPartyDtos.ClockDto;
import com.example.kido.media.dto.WatchPartyDtos.MemberDto;
import com.example.kido.media.dto.WatchPartyDtos.PendingGuestDto;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * The shared playhead, and the sockets it is pushed down.
 *
 * <p>Not persisted, for the same reason {@code PlaybackSession} is not: a position
 * written every couple of seconds would turn a film into a stream of database writes,
 * and the value is meaningless after a restart because the sockets holding the party
 * together are gone too. {@code WatchParty} keeps what survives — who was invited and
 * who was let in.
 *
 * <h2>The clock is an anchor, not a counter</h2>
 * A party stores a position and the instant it was true, and nothing advances it. Every
 * reader works out {@code position + elapsed} for itself. That means a slow tick, a
 * missed tick or a client that reconnects mid-film all land on the same answer, where a
 * counter incremented on a timer would drift by exactly as much as the timer did.
 *
 * <h2>Whose clock</h2>
 * Both timestamps in a frame come from this server, never from the host's device. A
 * phone with a badly set clock would otherwise drag the whole party off, and there is
 * no way to tell that phone apart from a correct one. Clients keep a running estimate
 * of their own offset from {@code serverNowEpochMillis}, which every frame carries.
 */
@Slf4j
@Service
public class WatchPartyRegistry {

    /** How often the clock is pushed to everyone who is not being told something else. */
    private static final long TICK_INTERVAL_SECONDS = 2;

    /** How often abandoned parties are looked for. */
    private static final long REAP_INTERVAL_SECONDS = 60;

    /**
     * No socket for this long and the party is closed.
     *
     * <p>Generous, because a party with nobody connected is the normal state between
     * "the host tapped watch together" and "the friends actually opened the link". What
     * it stops is a party nobody ever joined keeping its code live indefinitely.
     */
    private static final Duration ABANDONED_AFTER = Duration.ofMinutes(15);

    /**
     * How long a guest may wait on the host before being told no.
     *
     * <p>Five minutes rather than the one that would suit a host staring at the screen:
     * parties get arranged over chat well before anyone presses play, and a friend who
     * clicks the link early should not be refused for being punctual.
     */
    private static final Duration PENDING_EXPIRES_AFTER = Duration.ofMinutes(5);

    /** Send limits for the concurrent decorator: a slow client must not stall the tick. */
    private static final int SEND_TIME_LIMIT_MILLIS = 5_000;
    private static final int SEND_BUFFER_BYTES = 64 * 1024;

    private final WatchPartyRepository parties;
    private final WatchPartyMemberRepository members;
    private final ObjectMapper json;

    /** Keyed by party id. A party appears here only once someone connects. */
    private final Map<String, LiveParty> live = new ConcurrentHashMap<>();

    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "watch-party-ticker");
                thread.setDaemon(true);
                return thread;
            });

    public WatchPartyRegistry(WatchPartyRepository parties,
                              WatchPartyMemberRepository members,
                              ObjectMapper json) {
        this.parties = parties;
        this.members = members;
        this.json = json;
    }

    @PostConstruct
    void init() {
        ticker.scheduleWithFixedDelay(this::tickAll,
                TICK_INTERVAL_SECONDS, TICK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        ticker.scheduleWithFixedDelay(this::reapAbandoned,
                REAP_INTERVAL_SECONDS, REAP_INTERVAL_SECONDS, TimeUnit.SECONDS);
        ticker.scheduleWithFixedDelay(this::denyStaleRequests,
                REAP_INTERVAL_SECONDS, REAP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        ticker.shutdownNow();
    }

    // --- connections ---

    /**
     * Puts a member on the wire.
     *
     * <p>The raw session is wrapped so concurrent sends are safe: the ticker thread and
     * a host action arriving on an IO thread can otherwise write to one socket at the
     * same time, which the WebSocket API does not allow.
     *
     * <p>A second connection from the same member — a reopened app, a second tab —
     * replaces the first rather than doubling them up.
     */
    public void attach(WatchParty party, WatchPartyMember member, WebSocketSession raw) {
        LiveParty target = live.computeIfAbsent(party.getId(),
                id -> new LiveParty(id, party.getJoinCode()));

        target.connections.values().stream()
                .filter(existing -> existing.memberId.equals(member.getId()))
                .toList()
                .forEach(stale -> {
                    target.connections.remove(stale.session.getId());
                    closeQuietly(stale.session, CloseStatus.NORMAL.withReason("replaced"));
                });

        Connection connection = new Connection(
                new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_BYTES),
                member.getId(),
                member.getRole());
        target.connections.put(raw.getId(), connection);
        target.lastConnectedAtMillis = System.currentTimeMillis();

        // The newcomer needs the clock before the next tick, or they sit on a black
        // screen for up to two seconds wondering whether the party is running.
        send(connection, new ClockFrame(PartySocketDtos.TICK, snapshot(target), null));
        broadcastMembers(target);

        log.info("Watch party {} attached member={} role={} ({} online)",
                target.joinCode, member.getId(), member.getRole(), target.connections.size());
    }

    /**
     * Removes a dropped socket.
     *
     * <p>The host dropping pauses the party rather than ending it. A tunnel, a locked
     * phone or a flaky wifi should not destroy everyone's evening, and the film waiting
     * where it was is exactly what a room does when someone steps out. Ending is an
     * explicit act, over REST.
     */
    public void detach(WebSocketSession session) {
        for (LiveParty party : live.values()) {
            Connection gone = party.connections.remove(session.getId());
            if (gone == null) {
                continue;
            }
            party.lastConnectedAtMillis = System.currentTimeMillis();

            if (gone.role == PartyRole.HOST && party.state == PartyClockState.PLAYING) {
                anchor(party, impliedPosition(party), PartyClockState.PAUSED);
                broadcastClock(party, PartySocketDtos.PAUSE, "host disconnected");
                log.info("Watch party {} paused: the host dropped", party.joinCode);
            }
            broadcastMembers(party);
            return;
        }
    }

    // --- clock ---

    /**
     * Applies something the host did.
     *
     * @param positionSeconds where the host says they are; a seek is only meaningful
     *                        with one, and play/pause carry it so the party lands on
     *                        the host's real frame rather than its last estimate
     */
    public void hostAction(String partyId, String type, Double positionSeconds, String by) {
        LiveParty party = live.get(partyId);
        if (party == null) {
            return;
        }
        double position = positionSeconds == null ? impliedPosition(party) : positionSeconds;

        PartyClockState state = switch (type) {
            case PartySocketDtos.PLAY -> PartyClockState.PLAYING;
            case PartySocketDtos.PAUSE -> PartyClockState.PAUSED;
            default -> party.state;
        };

        anchor(party, position, state);
        broadcastClock(party, type, by);
    }

    /**
     * Folds in a position report.
     *
     * <p>From the host this re-anchors the party: their player is the authority, so if
     * they stalled for three seconds the party moves back with them rather than leaving
     * them behind their own film. From anyone else it only records the gap, which is
     * what the host's member list shows.
     */
    public void report(String partyId, String sessionId, Double positionSeconds, Boolean buffering) {
        LiveParty party = live.get(partyId);
        if (party == null) {
            return;
        }
        Connection connection = party.connections.get(sessionId);
        if (connection == null) {
            return;
        }

        if (buffering != null) {
            connection.buffering = buffering;
        }
        if (positionSeconds == null) {
            return;
        }

        if (connection.role == PartyRole.HOST) {
            anchor(party, positionSeconds, party.state);
            connection.driftSeconds = 0.0;
        } else {
            connection.driftSeconds = positionSeconds - impliedPosition(party);
        }
    }

    /** The clock as it stands, or empty for a party nobody has connected to yet. */
    public ClockDto clock(String partyId) {
        LiveParty party = live.get(partyId);
        return party == null ? null : snapshot(party);
    }

    /**
     * Ends a party on the wire: everyone is told why, then closed.
     *
     * <p>Called by the service after the row is marked ended, so a client that races
     * the close and retries over REST finds the code already gone.
     */
    public void endParty(String partyId, String reason) {
        LiveParty party = live.remove(partyId);
        if (party == null) {
            return;
        }
        for (Connection connection : party.connections.values()) {
            send(connection, new EndedFrame(PartySocketDtos.ENDED, reason));
            closeQuietly(connection.session, CloseStatus.NORMAL.withReason("party ended"));
        }
    }

    /** Tells one client its last message was refused, without dropping it. */
    public void refuse(String partyId, String sessionId, String message) {
        LiveParty party = live.get(partyId);
        if (party == null) {
            return;
        }
        Connection connection = party.connections.get(sessionId);
        if (connection != null) {
            send(connection, new ErrorFrame(PartySocketDtos.ERROR, message));
        }
    }

    // --- member list ---

    /**
     * Everyone admitted, annotated with what the sockets know.
     *
     * <p>Built here rather than in the service so the list a socket pushes and the list
     * REST returns cannot drift apart: a client polling the fallback must see the same
     * shape it would have been pushed.
     */
    public List<MemberDto> memberList(String partyId) {
        LiveParty party = live.get(partyId);

        return members.findByPartyIdAndStateOrderByRequestedAtAsc(partyId, MemberState.ADMITTED)
                .stream()
                .map(member -> {
                    Connection connection = party == null ? null : party.connectionOf(member.getId());
                    return new MemberDto(
                            member.getId(),
                            member.getDisplayName(),
                            member.getRole(),
                            member.getRole() == PartyRole.HOST,
                            member.getJoinedAt(),
                            connection != null,
                            connection != null && connection.buffering,
                            connection == null ? null : connection.driftSeconds);
                })
                .toList();
    }

    /** Pushes the member list after an admission or departure the socket layer missed. */
    public void announceMembers(String partyId) {
        LiveParty party = live.get(partyId);
        if (party != null) {
            broadcastMembers(party);
        }
    }

    /**
     * Puts a knock in front of the host, and nobody else.
     *
     * <p>Sent only down host connections. The payload is a name a stranger typed and
     * only the host can act on it; broadcasting it would show every member a prompt
     * they cannot answer, and hand them a stranger's chosen text.
     */
    public void announcePending(String partyId, PendingGuestDto guest) {
        LiveParty party = live.get(partyId);
        if (party == null) {
            return;
        }
        PendingFrame frame = new PendingFrame(PartySocketDtos.PENDING, guest);
        party.connections.values().stream()
                .filter(connection -> connection.role == PartyRole.HOST)
                .forEach(connection -> send(connection, frame));
    }

    /** Which party a socket belongs to, for routing an inbound frame. */
    public String partyOf(String sessionId) {
        return live.values().stream()
                .filter(party -> party.connections.containsKey(sessionId))
                .map(party -> party.partyId)
                .findFirst()
                .orElse(null);
    }

    // --- internals ---

    private void anchor(LiveParty party, double positionSeconds, PartyClockState state) {
        party.positionSeconds = Math.max(0, positionSeconds);
        party.anchoredAtMillis = System.currentTimeMillis();
        party.state = state;
    }

    private static double impliedPosition(LiveParty party) {
        if (party.state != PartyClockState.PLAYING) {
            return party.positionSeconds;
        }
        return party.positionSeconds
                + (System.currentTimeMillis() - party.anchoredAtMillis) / 1000.0;
    }

    private static ClockDto snapshot(LiveParty party) {
        return new ClockDto(party.state, party.positionSeconds,
                party.anchoredAtMillis, System.currentTimeMillis());
    }

    private void tickAll() {
        for (LiveParty party : live.values()) {
            if (!party.connections.isEmpty()) {
                broadcastClock(party, PartySocketDtos.TICK, null);
            }
        }
    }

    /**
     * Closes parties nobody is connected to any more.
     *
     * <p>Works off the database rather than the live map, because the parties that leak
     * are exactly the ones that never appeared in it: created over REST, never joined,
     * their code valid forever. A party that was live once is timed from when its last
     * socket went away instead.
     */
    private void reapAbandoned() {
        long cutoff = System.currentTimeMillis() - ABANDONED_AFTER.toMillis();
        for (WatchParty party : parties.findByEndedAtIsNull()) {
            LiveParty tracked = live.get(party.getId());
            if (tracked != null && !tracked.connections.isEmpty()) {
                continue;
            }
            long idleSince = tracked == null
                    ? party.getCreatedAt().toEpochMilli()
                    : tracked.lastConnectedAtMillis;
            if (idleSince > cutoff) {
                continue;
            }
            party.setEndedAt(Instant.now());
            parties.save(party);
            endParty(party.getId(), "nobody was watching");
            log.info("Watch party {} closed: abandoned", party.getJoinCode());
        }
    }

    /**
     * Closes out join requests the host never answered.
     *
     * <p>A guest left on "waiting" forever is the worst of the three outcomes: they
     * cannot tell a host who is thinking from a host who put the phone down, so they
     * keep polling. Timing out into a denial makes the app able to say so.
     *
     * <p>Lives here rather than in the service only because this is where the
     * scheduler is. It touches membership, not the playhead.
     */
    private void denyStaleRequests() {
        Instant cutoff = Instant.now().minus(PENDING_EXPIRES_AFTER);
        for (WatchPartyMember stale
                : members.findByStateAndRequestedAtBefore(MemberState.PENDING, cutoff)) {
            stale.setState(MemberState.DENIED);
            members.save(stale);
            announceMembers(stale.getPartyId());
            log.info("Watch party join request from '{}' expired unanswered",
                    stale.getDisplayName());
        }
    }

    private void broadcastClock(LiveParty party, String type, String by) {
        broadcast(party, new ClockFrame(type, snapshot(party), by));
    }

    private void broadcastMembers(LiveParty party) {
        broadcast(party, new MembersFrame(PartySocketDtos.MEMBERS, memberList(party.partyId)));
    }

    private void broadcast(LiveParty party, Object frame) {
        String payload = encode(frame);
        if (payload == null) {
            return;
        }
        for (Connection connection : party.connections.values()) {
            send(connection, payload);
        }
    }

    private void send(Connection connection, Object frame) {
        String payload = encode(frame);
        if (payload != null) {
            send(connection, payload);
        }
    }

    /**
     * Writes one frame, dropping the connection if it will not take it.
     *
     * <p>A send that fails means the socket is gone in a way the container has not told
     * us about yet. Leaving it in the map would have every subsequent tick pay for it.
     */
    private void send(Connection connection, String payload) {
        try {
            connection.session.sendMessage(new TextMessage(payload));
        } catch (IOException | IllegalStateException ex) {
            log.debug("Dropping watch party socket {}: {}",
                    connection.session.getId(), ex.toString());
            closeQuietly(connection.session, CloseStatus.SERVER_ERROR);
            live.values().forEach(party -> party.connections.remove(connection.session.getId()));
        }
    }

    private String encode(Object frame) {
        try {
            return json.writeValueAsString(frame);
        } catch (Exception ex) {
            log.warn("Could not encode watch party frame {}", frame.getClass().getSimpleName(), ex);
            return null;
        }
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException | IllegalStateException ex) {
            log.debug("Close failed for {}: {}", session.getId(), ex.toString());
        }
    }

    /** One party's live state. Read by the ticker thread, written by IO threads. */
    private static final class LiveParty {

        private final String partyId;
        private final String joinCode;
        private final Map<String, Connection> connections = new ConcurrentHashMap<>();

        private volatile PartyClockState state = PartyClockState.PAUSED;
        private volatile double positionSeconds;
        private volatile long anchoredAtMillis = System.currentTimeMillis();
        private volatile long lastConnectedAtMillis = System.currentTimeMillis();

        private LiveParty(String partyId, String joinCode) {
            this.partyId = partyId;
            this.joinCode = joinCode;
        }

        private Connection connectionOf(String memberId) {
            return connections.values().stream()
                    .filter(connection -> connection.memberId.equals(memberId))
                    .findFirst()
                    .orElse(null);
        }
    }

    /** One open socket. */
    private static final class Connection {

        private final WebSocketSession session;
        private final String memberId;
        private final PartyRole role;

        private volatile boolean buffering;
        private volatile Double driftSeconds;

        private Connection(WebSocketSession session, String memberId, PartyRole role) {
            this.session = session;
            this.memberId = memberId;
            this.role = role;
        }
    }
}
