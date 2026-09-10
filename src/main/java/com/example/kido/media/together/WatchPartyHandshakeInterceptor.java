package com.example.kido.media.together;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import com.example.kido.media.together.WatchPartyService.SocketIdentity;
import com.example.kido.security.JwtService;
import com.example.kido.user.AppUser;
import com.example.kido.user.UserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Authenticates a watch party socket at the handshake, before any frame is read.
 *
 * <h2>Why the token is in the query string</h2>
 * A browser cannot set an {@code Authorization} header on a WebSocket — the API has no
 * way to express it — so {@code JwtAuthFilter} has nothing to work with on this path
 * and the security chain lets the handshake through unauthenticated. Authentication
 * happens here instead, and a request that fails it never becomes a socket.
 *
 * <p>The cost is a credential in a URL, where it can reach access logs. It is mitigated
 * by what the token can do rather than by hiding it: this one is the caller's ordinary
 * account token, already required on every other API call, and a guest token is scoped
 * to a single title for the life of one party.
 */
@Slf4j
@Component
public class WatchPartyHandshakeInterceptor implements HandshakeInterceptor {

    /** Where {@link WatchPartySocketHandler} finds who connected. */
    public static final String IDENTITY = "watchPartyIdentity";

    private final JwtService jwt;
    private final UserRepository users;
    private final WatchPartyService parties;

    public WatchPartyHandshakeInterceptor(JwtService jwt,
                                          UserRepository users,
                                          WatchPartyService parties) {
        this.jwt = jwt;
        this.users = users;
        this.parties = parties;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler handler,
                                   Map<String, Object> attributes) {

        var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
        String code = query.getFirst("code");
        String token = query.getFirst("token");

        if (code == null || code.isBlank() || token == null || token.isBlank()) {
            return refuse(response, HttpStatus.BAD_REQUEST, "missing code or token");
        }

        // Guests are checked first and without a database round trip: their token says
        // what it is, and an account token fails the claim check immediately.
        var guest = jwt.extractGuest(token);
        if (guest.isPresent()) {
            return admit(response, attributes,
                    parties.authoriseGuestSocket(code, guest.get().memberId()));
        }

        String userId = jwt.extractUserId(token);
        if (userId == null) {
            return refuse(response, HttpStatus.UNAUTHORIZED, "invalid or expired token");
        }

        AppUser user = users.findById(userId).orElse(null);
        if (user == null) {
            return refuse(response, HttpStatus.UNAUTHORIZED, "no such account");
        }

        return admit(response, attributes, parties.authoriseSocket(code, user.getId()));
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler handler,
                               Exception exception) {
        // Nothing to do: the handler takes over from here.
    }

    /**
     * Opens the socket, or refuses it.
     *
     * <p>Not in the party, never was, or the party is over all give the same answer, so
     * a caller cannot use the handshake to work out which codes are real.
     */
    private static boolean admit(ServerHttpResponse response,
                                 Map<String, Object> attributes,
                                 Optional<SocketIdentity> identity) {
        return identity.map(resolved -> {
            attributes.put(IDENTITY, resolved);
            return true;
        }).orElseGet(() -> refuse(response, HttpStatus.FORBIDDEN, "not in this party"));
    }

    private static boolean refuse(ServerHttpResponse response, HttpStatus status, String why) {
        log.debug("Watch party handshake refused ({}): {}", status.value(), why);
        response.setStatusCode(status);
        return false;
    }
}
