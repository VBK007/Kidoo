package com.example.kido.media.stream;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.common.ClientAddress;
import com.example.kido.media.dto.PlaybackDtos.ClientCapabilitiesRequest;
import com.example.kido.media.dto.PlaybackDtos.PlaybackDecisionDto;
import com.example.kido.media.together.WatchPartyGrants;
import com.example.kido.security.GuestPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * How a watch party guest starts playing.
 *
 * <p>A separate endpoint from {@code /api/media/items/{id}/playback-decision} because
 * that one is profile-scoped and a guest has no profile — {@code ActiveProfileResolver}
 * refuses any principal that is not an {@code AppUser}, which is the check keeping
 * guests out of the rest of the API and is not worth weakening for this.
 *
 * <p>There is no item in the path either. A guest does not choose what to watch: the
 * title is in their token, put there when the host admitted them. Taking it from the
 * request would mean trusting the caller to name the thing they are allowed to play.
 *
 * <p>Everything after this call is shared with accounts. The decision hands back a
 * stream or playlist URL, and those endpoints check the same grant.
 */
@RestController
@RequestMapping("/api/media/guest")
public class GuestPlaybackController {

    private final PlaybackStarter starter;
    private final WatchPartyGrants grants;

    public GuestPlaybackController(PlaybackStarter starter, WatchPartyGrants grants) {
        this.starter = starter;
        this.grants = grants;
    }

    @PostMapping("/playback-decision")
    public PlaybackDecisionDto decide(@AuthenticationPrincipal GuestPrincipal guest,
                                      @RequestParam(defaultValue = "0") double startSeconds,
                                      @Valid @RequestBody ClientCapabilitiesRequest capabilities,
                                      HttpServletRequest request) {

        // Re-checks that the party is still live. The security chain proved the token
        // is real; only this proves the evening is still on.
        grants.requirePlayable(guest, guest.mediaItemId());

        // Filed under the member id, which is how ending the party finds this stream
        // again to stop it: a guest has no profile for the registry to be searched by.
        return starter.start(
                guest.memberId(),
                guest.displayName() + " (guest)",
                guest.mediaItemId(),
                startSeconds,
                capabilities,
                ClientAddress.resolve(request));
    }
}
