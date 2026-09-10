package com.example.kido.media.together;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.common.ClientAddress;
import com.example.kido.media.dto.WatchPartyDtos.AdmitRequest;
import com.example.kido.media.dto.WatchPartyDtos.CreatePartyRequest;
import com.example.kido.media.dto.WatchPartyDtos.GuestJoinDto;
import com.example.kido.media.dto.WatchPartyDtos.GuestJoinRequest;
import com.example.kido.media.dto.WatchPartyDtos.PartyDto;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;
import com.example.kido.user.AppUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Watch party membership, for people who have an account on this server.
 *
 * <p>Every endpoint here sits behind the JWT filter and takes both the account and the
 * active profile: the account owns the party, the profile is who the other members see
 * in the list and whose resume point moves.
 *
 * <p>{@code GET /state} is the fallback for clients that cannot hold a socket open. It
 * answers the same shape the socket pushes, so a client can poll it every couple of
 * seconds and behave identically, only later.
 */
@RestController
@RequestMapping("/api/parties")
public class WatchPartyController {

    private final WatchPartyService service;

    public WatchPartyController(WatchPartyService service) {
        this.service = service;
    }

    /** Opens a party around a title and returns the code to share. */
    @PostMapping
    public ResponseEntity<PartyDto> create(@AuthenticationPrincipal AppUser user,
                                           @ActiveProfile Profile profile,
                                           @Valid @RequestBody CreatePartyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user, profile, request));
    }

    /**
     * Joins with a code.
     *
     * <p>Not idempotent in the strict sense — it moves a row that had left back to
     * admitted — but safe to call repeatedly, which matters because a client that loses
     * its connection will call it again on reconnect.
     */
    @PostMapping("/{code}/join")
    public PartyDto join(@AuthenticationPrincipal AppUser user,
                         @ActiveProfile Profile profile,
                         @PathVariable String code) {
        return service.join(user, profile, code);
    }

    @GetMapping("/{code}/state")
    public PartyDto state(@AuthenticationPrincipal AppUser user, @PathVariable String code) {
        return service.state(user, code);
    }

    /**
     * Asks to join with a code and a name, holding no account and no token.
     *
     * <p>Public — the caller has nothing to authenticate with yet. What comes back is
     * normally a place in a queue, not a credential: the host has to accept first.
     */
    @PostMapping("/{code}/guest")
    public GuestJoinDto requestGuestSeat(@PathVariable String code,
                                         @Valid @RequestBody GuestJoinRequest request,
                                         HttpServletRequest http) {
        return service.requestGuestSeat(code, request, ClientAddress.resolve(http));
    }

    /**
     * Checks whether the host has answered yet.
     *
     * <p>Polled rather than pushed because a pending guest has no socket: they are not
     * in the party, so there is nothing to hold open until they are. The secret from
     * the first response is what proves this is the device that asked.
     */
    @GetMapping("/{code}/guest/{requestId}")
    public GuestJoinDto pollGuestSeat(@PathVariable String code,
                                      @PathVariable String requestId,
                                      @RequestParam("token") String pollToken) {
        return service.pollGuestSeat(code, requestId, pollToken);
    }

    /** The host accepting or refusing one waiting guest. */
    @PostMapping("/{code}/admit")
    public PartyDto admitGuest(@AuthenticationPrincipal AppUser user,
                               @PathVariable String code,
                               @Valid @RequestBody AdmitRequest request) {
        return service.admitGuest(user, code, request);
    }

    @PostMapping("/{code}/leave")
    public ResponseEntity<Void> leave(@AuthenticationPrincipal AppUser user,
                                      @PathVariable String code) {
        service.leave(user, code);
        return ResponseEntity.noContent().build();
    }

    /** Ends the party for everyone. Host only. */
    @DeleteMapping("/{code}")
    public ResponseEntity<Void> end(@AuthenticationPrincipal AppUser user,
                                    @PathVariable String code) {
        service.end(user, code);
        return ResponseEntity.noContent().build();
    }
}
