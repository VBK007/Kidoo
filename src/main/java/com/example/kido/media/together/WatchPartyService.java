package com.example.kido.media.together;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.dto.WatchPartyDtos.AdmitRequest;
import com.example.kido.media.dto.WatchPartyDtos.CreatePartyRequest;
import com.example.kido.media.dto.WatchPartyDtos.GuestJoinDto;
import com.example.kido.media.dto.WatchPartyDtos.GuestJoinRequest;
import com.example.kido.media.dto.WatchPartyDtos.PartyDto;
import com.example.kido.media.dto.WatchPartyDtos.PendingGuestDto;
import com.example.kido.media.session.PlaybackSessionRegistry;
import com.example.kido.profile.Profile;
import com.example.kido.security.JwtService;
import com.example.kido.user.AppUser;

import lombok.extern.slf4j.Slf4j;

/**
 * Opening, joining and closing a watch party.
 *
 * <p>This is the membership half only. The shared playhead lives in the socket layer,
 * so {@link PartyDto#clock()} is null everywhere here — a client polling this service
 * alone learns who is in the party but not where the film has got to.
 *
 * <h2>Why joining needs no grant</h2>
 * A friend with an account on this server can already reach any title: {@code MediaItem}
 * has no owner column, {@code CatalogService.require} is unscoped, and
 * {@code StreamController.stream} takes no profile at all. What stops them today is
 * discovery, not authorisation. So for members a party is purely a rendezvous — a code
 * saying which id to play, and a clock keeping everyone on the same frame. Guests are
 * the case that genuinely needs a new authorisation path, and they are handled apart
 * from this.
 */
@Slf4j
@Service
public class WatchPartyService {

    /**
     * Code alphabet with {@code 0}, {@code O}, {@code 1}, {@code I} and {@code L}
     * removed.
     *
     * <p>31 characters over 6 places is about 887 million codes, ample for a value that
     * lives one evening. The removals matter more than the size: this gets read aloud
     * across a room and retyped on a TV remote.
     */
    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

    private static final int CODE_LENGTH = 6;

    /** Collision retries before giving up; exhausting these means something is wrong. */
    private static final int CODE_ATTEMPTS = 12;

    /**
     * How long a guest token stays valid on its own terms.
     *
     * <p>A backstop rather than the real limit. What revokes a guest is the party
     * ending, checked against the database wherever the token is used — a film plus
     * the arguing about what to watch next fits inside four hours, and anything longer
     * is a party that should have been reopened.
     */
    private static final Duration GUEST_TOKEN_TTL = Duration.ofHours(4);

    /** Matches the column, and what an accept prompt can show without wrapping. */
    private static final int MAX_NAME_LENGTH = 40;

    private final WatchPartyRepository parties;
    private final WatchPartyMemberRepository members;
    private final CatalogService catalog;
    private final MediaItemRepository items;
    private final WatchPartyRegistry registry;
    private final PlaybackSessionRegistry playbackSessions;
    private final GuestJoinThrottle throttle;
    private final JwtService jwt;
    private final SecureRandom random = new SecureRandom();

    public WatchPartyService(WatchPartyRepository parties,
                             WatchPartyMemberRepository members,
                             CatalogService catalog,
                             MediaItemRepository items,
                             WatchPartyRegistry registry,
                             PlaybackSessionRegistry playbackSessions,
                             GuestJoinThrottle throttle,
                             JwtService jwt) {
        this.parties = parties;
        this.members = members;
        this.catalog = catalog;
        this.items = items;
        this.registry = registry;
        this.playbackSessions = playbackSessions;
        this.throttle = throttle;
        this.jwt = jwt;
    }

    /**
     * Who a socket handshake belongs to.
     *
     * <p>Both halves are detached entities read once at the handshake and carried for
     * the life of the connection. Nothing here is re-read per frame: a socket that
     * should no longer exist is closed by whatever ended the party, not by every frame
     * paying for a database round trip.
     */
    public record SocketIdentity(WatchParty party, WatchPartyMember member) {}

    /**
     * Resolves a socket handshake to a seat, or refuses it.
     *
     * <p>Returns empty rather than throwing for every failure — an unknown code, an
     * ended party, someone who never joined — so the handshake answers all of them
     * identically and cannot be used to work out which codes are real.
     */
    @Transactional(readOnly = true)
    public Optional<SocketIdentity> authoriseSocket(String code, String accountId) {
        return parties.findByJoinCodeAndEndedAtIsNull(normalise(code))
                .flatMap(party -> members.findByPartyIdAndAccountId(party.getId(), accountId)
                        .filter(WatchPartyMember::isAdmitted)
                        .map(member -> new SocketIdentity(party, member)));
    }

    /**
     * Opens a party around a title and seats the host in it.
     *
     * <p>Any live party this account already hosts is ended first. Two parties from one
     * host is never what someone meant — they tapped "watch together" again because the
     * first attempt reached nobody — and leaving the old one running would keep its code
     * valid and its seats reserved.
     */
    @Transactional
    public PartyDto create(AppUser user, Profile profile, CreatePartyRequest request) {
        MediaItem item = catalog.require(request.mediaItemId());

        parties.findByHostAccountIdAndEndedAtIsNullOrderByCreatedAtDesc(user.getId())
                .forEach(existing -> close(existing, "replaced by a new party"));

        WatchParty party = parties.save(WatchParty.builder()
                .joinCode(mintCode())
                .hostAccountId(user.getId())
                .hostProfileId(profile.getId())
                .mediaItemId(item.getId())
                .maxMembers(request.maxMembers() == null
                        ? WatchParty.DEFAULT_MAX_MEMBERS
                        : request.maxMembers())
                .requireApprovalForGuests(request.requireApprovalForGuests() == null
                        || request.requireApprovalForGuests())
                .build());

        Instant now = Instant.now();
        members.save(WatchPartyMember.builder()
                .partyId(party.getId())
                .role(PartyRole.HOST)
                .state(MemberState.ADMITTED)
                .accountId(user.getId())
                .profileId(profile.getId())
                .displayName(profile.getName())
                .joinedAt(now)
                .lastSeenAt(now)
                .build());

        log.info("Watch party {} opened by '{}' for '{}'",
                party.getJoinCode(), profile.getName(), item.getTitle());
        return view(party, item, user);
    }

    /**
     * Seats an account holder who typed the code.
     *
     * <p>No approval step. An account on this server can already stream the title
     * directly, so gating the rendezvous would protect nothing. Approval exists for
     * guests, who have no account behind them.
     */
    @Transactional
    public PartyDto join(AppUser user, Profile profile, String code) {
        WatchParty party = requireLive(code);

        WatchPartyMember member = members
                .findByPartyIdAndAccountId(party.getId(), user.getId())
                .orElse(null);

        if (member == null) {
            requireFreeSeat(party);
            member = WatchPartyMember.builder()
                    .partyId(party.getId())
                    .role(PartyRole.MEMBER)
                    .state(MemberState.ADMITTED)
                    .accountId(user.getId())
                    .profileId(profile.getId())
                    .displayName(profile.getName())
                    .joinedAt(Instant.now())
                    .build();
        } else if (!member.isAdmitted()) {
            // Rejoining after a drop reuses the row rather than adding a second, and
            // may well arrive as a different profile of the same account.
            requireFreeSeat(party);
            member.setState(MemberState.ADMITTED);
            member.setJoinedAt(Instant.now());
            member.setProfileId(profile.getId());
            member.setDisplayName(profile.getName());
        }

        member.setLastSeenAt(Instant.now());
        members.save(member);

        // Whoever is already connected should see the new name without waiting for
        // their own next poll — joining over REST is invisible to the socket layer.
        registry.announceMembers(party.getId());

        log.info("'{}' joined watch party {}", profile.getName(), party.getJoinCode());
        return view(party, findItem(party), user);
    }

    /**
     * Takes a knock at the door from someone with no account.
     *
     * <p>Public, and the only endpoint here that is. Three things keep it safe: a live
     * code is required, the address is throttled, and — unless the host turned it off —
     * what comes back is a place in a queue rather than a credential. Approval is the
     * one that matters. Holding a code is weak evidence of an invitation: codes get
     * forwarded, screenshotted and read over shoulders, and the host is by definition
     * sitting in front of the screen when it happens.
     */
    @Transactional
    public GuestJoinDto requestGuestSeat(String code, GuestJoinRequest request, String address) {
        if (!throttle.allow(address)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts; wait a minute and try again");
        }
        WatchParty party = requireLive(code);

        // Bounded so a flood cannot bury the host's accept prompt, or fill the table
        // on the strength of one leaked code.
        long waiting = members.countByPartyIdAndState(party.getId(), MemberState.PENDING);
        if (waiting >= party.getMaxMembers() * 3L) {
            throw new ApiException(HttpStatus.CONFLICT, "Too many people are already waiting");
        }

        boolean auto = !party.isRequireApprovalForGuests();
        if (auto) {
            requireFreeSeat(party);
        }

        Instant now = Instant.now();
        WatchPartyMember guest = members.save(WatchPartyMember.builder()
                .partyId(party.getId())
                .role(PartyRole.GUEST)
                .state(auto ? MemberState.ADMITTED : MemberState.PENDING)
                .displayName(sanitiseName(request.name()))
                .pollToken(mintPollToken())
                .requestedAt(now)
                .joinedAt(auto ? now : null)
                .build());

        if (auto) {
            registry.announceMembers(party.getId());
            log.info("Guest '{}' walked into watch party {}", guest.getDisplayName(), party.getJoinCode());
            return GuestJoinDto.admitted(guest.getId(), issueGuestToken(party, guest), guestTokenExpiry());
        }

        registry.announcePending(party.getId(),
                new PendingGuestDto(guest.getId(), guest.getDisplayName(), guest.getRequestedAt()));
        log.info("Guest '{}' is waiting on watch party {}", guest.getDisplayName(), party.getJoinCode());
        return GuestJoinDto.pending(guest.getId(), guest.getPollToken());
    }

    /**
     * Answers a waiting guest.
     *
     * <p>Also how an admitted guest recovers a token after their app restarts, which is
     * why the poll secret outlives the first successful poll: the alternative is
     * knocking again and making the host approve the same person twice.
     */
    @Transactional(readOnly = true)
    public GuestJoinDto pollGuestSeat(String code, String requestId, String pollToken) {
        WatchParty party = requireLive(code);

        WatchPartyMember guest = members.findByIdAndPollToken(requestId, pollToken)
                .filter(member -> member.getPartyId().equals(party.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such request"));

        return switch (guest.getState()) {
            case PENDING -> GuestJoinDto.stillPending(guest.getId());
            case ADMITTED -> GuestJoinDto.admitted(
                    guest.getId(), issueGuestToken(party, guest), guestTokenExpiry());
            // A guest who left is told the same as one who was refused. There is
            // nothing useful to distinguish, and both need to knock again.
            default -> GuestJoinDto.denied(guest.getId());
        };
    }

    /** The host's yes or no to one waiting guest. */
    @Transactional
    public PartyDto admitGuest(AppUser user, String code, AdmitRequest request) {
        WatchParty party = requireLive(code);
        if (!party.getHostAccountId().equals(user.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the host can admit guests");
        }

        WatchPartyMember guest = members.findById(request.requestId())
                .filter(member -> member.getPartyId().equals(party.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such request"));

        if (guest.getState() != MemberState.PENDING) {
            // Answered already, by the other half of a double tap or by the timeout.
            throw new ApiException(HttpStatus.CONFLICT, "That request has already been answered");
        }

        if (request.allow()) {
            requireFreeSeat(party);
            guest.setState(MemberState.ADMITTED);
            guest.setJoinedAt(Instant.now());
        } else {
            guest.setState(MemberState.DENIED);
        }
        members.save(guest);
        registry.announceMembers(party.getId());

        log.info("Watch party {}: guest '{}' was {}", party.getJoinCode(),
                guest.getDisplayName(), request.allow() ? "admitted" : "refused");
        return view(party, findItem(party), user);
    }

    /**
     * Resolves a guest's socket handshake.
     *
     * <p>Separate from {@link #authoriseSocket} because a guest is identified by their
     * member row rather than by an account, and because their party is re-checked here:
     * the token outlives the party by design, so this is where that stops mattering.
     */
    @Transactional(readOnly = true)
    public Optional<SocketIdentity> authoriseGuestSocket(String code, String memberId) {
        return parties.findByJoinCodeAndEndedAtIsNull(normalise(code))
                .flatMap(party -> members.findById(memberId)
                        .filter(member -> member.getPartyId().equals(party.getId()))
                        .filter(WatchPartyMember::isAdmitted)
                        .map(member -> new SocketIdentity(party, member)));
    }

    private String issueGuestToken(WatchParty party, WatchPartyMember guest) {
        return jwt.generateGuestToken(guest.getId(), party.getId(), party.getMediaItemId(),
                guest.getDisplayName(), guestTokenExpiry());
    }

    private static Instant guestTokenExpiry() {
        return Instant.now().plus(GUEST_TOKEN_TTL);
    }

    /**
     * Cleans a name a stranger typed before the host is shown it.
     *
     * <p>Control characters become spaces, runs of whitespace collapse, and the length
     * is capped. The point is that an accept prompt cannot be dressed up to read as
     * something other than one person asking to join — a newline in a name is how you
     * make one line look like two.
     *
     * <p>Replaced rather than deleted, so a tab between two words leaves two words.
     */
    private static String sanitiseName(String raw) {
        String cleaned = raw.codePoints()
                .map(codePoint -> Character.isISOControl(codePoint) ? ' ' : codePoint)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() > MAX_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_NAME_LENGTH).trim();
        }
        return cleaned.isEmpty() ? "Guest" : cleaned;
    }

    private String mintPollToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The party as one member sees it. Non-members get a 403 rather than a peek. */
    @Transactional(readOnly = true)
    public PartyDto state(AppUser user, String code) {
        WatchParty party = requireLive(code);
        requireMember(party, user);
        return view(party, findItem(party), user);
    }

    /**
     * Drops someone out.
     *
     * <p>The host leaving ends the party for everyone. There is no promoting a member:
     * the host's player <em>is</em> the clock, so a handover would mean adopting a
     * different device's playhead mid-film and every remaining member seeing a jump.
     */
    @Transactional
    public void leave(AppUser user, String code) {
        WatchParty party = requireLive(code);

        if (party.getHostAccountId().equals(user.getId())) {
            close(party, "host left");
            return;
        }

        members.findByPartyIdAndAccountId(party.getId(), user.getId()).ifPresent(member -> {
            member.setState(MemberState.LEFT);
            member.setLastSeenAt(Instant.now());
            members.save(member);
        });
        registry.announceMembers(party.getId());
    }

    @Transactional
    public void end(AppUser user, String code) {
        WatchParty party = requireLive(code);
        if (!party.getHostAccountId().equals(user.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the host can end this party");
        }
        close(party, "host ended it");
    }

    /**
     * Marks a party finished.
     *
     * <p>Ending invalidates the code, blocks new joins, stops the streams the party was
     * the only authority for, and closes every socket with a reason so the other
     * devices can say what happened rather than just going quiet.
     *
     * <p>The row is marked ended first, so a client that races the close and retries
     * over REST finds the code already gone.
     */
    private void close(WatchParty party, String reason) {
        party.setEndedAt(Instant.now());
        parties.save(party);
        stopGuestStreams(party);
        registry.endParty(party.getId(), reason);
        log.info("Watch party {} ended: {}", party.getJoinCode(), reason);
    }

    /**
     * Cuts off the viewers whose only entitlement was this party.
     *
     * <p>Guests only, and the asymmetry is deliberate. A member with an account could
     * have played this title without any party at all — the library has no owner and
     * the stream endpoint takes no profile — so stopping them would be the server
     * overruling an entitlement it never granted. Their client is told the party ended
     * and decides for itself whether to keep watching alone. A guest has no such
     * entitlement: theirs existed only while the party did.
     *
     * <p>The grant check already refuses a guest's next request, so what this adds is
     * immediacy. A transcode's ffmpeg process dies now rather than after the session
     * registry's idle timeout, and the owner stops seeing someone who has left.
     */
    private void stopGuestStreams(WatchParty party) {
        members.findByPartyIdAndStateOrderByRequestedAtAsc(party.getId(), MemberState.ADMITTED)
                .stream()
                .filter(WatchPartyMember::isGuest)
                .forEach(guest -> playbackSessions.terminateFor(
                        guest.getId(), party.getMediaItemId()));
    }

    private WatchParty requireLive(String code) {
        return parties.findByJoinCodeAndEndedAtIsNull(normalise(code))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No live party with that code"));
    }

    private void requireMember(WatchParty party, AppUser user) {
        members.findByPartyIdAndAccountId(party.getId(), user.getId())
                .filter(WatchPartyMember::isAdmitted)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                        "You are not in this party"));
    }

    private void requireFreeSeat(WatchParty party) {
        long seated = members.countByPartyIdAndState(party.getId(), MemberState.ADMITTED);
        if (seated >= party.getMaxMembers()) {
            throw new ApiException(HttpStatus.CONFLICT, "This party is full");
        }
    }

    /**
     * Looks the title up without insisting the file is reachable.
     *
     * <p>{@code CatalogService.require} throws {@code GONE} for a missing file, which is
     * right when starting playback and wrong when rendering a member list: an unplugged
     * disk should not make the party itself un-viewable.
     */
    private MediaItem findItem(WatchParty party) {
        return items.findById(party.getMediaItemId()).orElse(null);
    }

    private PartyDto view(WatchParty party, MediaItem item, AppUser viewer) {
        boolean host = party.getHostAccountId().equals(viewer.getId());

        // Built by the registry, not here, so the list this returns and the list the
        // socket pushes cannot drift apart — a client on the polling fallback has to
        // see exactly what it would have been pushed.
        var seated = registry.memberList(party.getId());

        // Pending requests are the host's business alone: the list carries names typed
        // by strangers, and only the host can act on it.
        List<PendingGuestDto> pending = host
                ? members.findByPartyIdAndStateOrderByRequestedAtAsc(
                                party.getId(), MemberState.PENDING)
                        .stream()
                        .map(member -> new PendingGuestDto(
                                member.getId(), member.getDisplayName(), member.getRequestedAt()))
                        .toList()
                : List.of();

        return new PartyDto(
                party.getId(),
                party.getJoinCode(),
                party.getMediaItemId(),
                item == null ? "Unknown title" : item.getTitle(),
                party.isLive(),
                party.getMaxMembers(),
                host,
                seated,
                pending,
                registry.clock(party.getId()),
                capacityWarning(party, item));
    }

    /**
     * Advisory only — the party is created either way.
     *
     * <p>Read from recorded history rather than a fresh decision, because a decision
     * needs the client's capabilities and the answer differs per device. A file that has
     * been played and has never once direct-played is the one case where the warning is
     * certain rather than a guess.
     */
    private static String capacityWarning(WatchParty party, MediaItem item) {
        if (item == null || !item.alwaysTranscodes()) {
            return null;
        }
        return "This title has always needed transcoding, so each of the "
                + party.getMaxMembers() + " seats costs its own ffmpeg process.";
    }

    private String mintCode() {
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                code.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            }
            String candidate = code.toString();
            if (!parties.existsByJoinCode(candidate)) {
                return candidate;
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Could not allocate a join code; try again");
    }

    /** Codes get pasted with spaces and hyphens around them, and typed in lower case. */
    private static String normalise(String code) {
        return code == null ? "" : code.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }
}
