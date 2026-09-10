package com.example.kido.media.together;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.example.kido.media.dto.PartySocketDtos.InboundFrame;
import com.example.kido.media.together.WatchPartyService.SocketIdentity;
import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * The watch party socket: host controls in, clock frames out.
 *
 * <p>Authentication and membership are settled before this class sees anything — the
 * handshake interceptor puts a {@link SocketIdentity} in the session attributes or the
 * socket is never opened. What is left here is routing, and the one rule that matters:
 * only the host may move the playhead.
 *
 * <p>All state lives in {@link WatchPartyRegistry}. This class holds none, so a second
 * connection from the same person, a dropped socket and a server restart are all the
 * registry's problem rather than three separate bugs waiting here.
 */
@Slf4j
@Component
public class WatchPartySocketHandler extends TextWebSocketHandler {

    private final WatchPartyRegistry registry;
    private final ObjectMapper json;

    public WatchPartySocketHandler(WatchPartyRegistry registry, ObjectMapper json) {
        this.registry = registry;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SocketIdentity identity = identityOf(session);
        if (identity == null) {
            // Cannot happen while the interceptor is registered, and is worth failing
            // loudly rather than serving an unidentified socket if it ever does.
            log.warn("Watch party socket {} arrived with no identity; closing", session.getId());
            close(session);
            return;
        }
        registry.attach(identity.party(), identity.member(), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SocketIdentity identity = identityOf(session);
        if (identity == null) {
            return;
        }
        String partyId = identity.party().getId();

        InboundFrame frame;
        try {
            frame = json.readValue(message.getPayload(), InboundFrame.class);
        } catch (Exception ex) {
            registry.refuse(partyId, session.getId(), "could not read that frame");
            return;
        }
        if (frame == null || frame.type() == null) {
            registry.refuse(partyId, session.getId(), "frame needs a type");
            return;
        }

        if (frame.isControl()) {
            if (identity.member().getRole() != PartyRole.HOST) {
                // Refused, not disconnected: a member sending controls is a client bug,
                // and dropping the socket would bury it under a reconnect.
                registry.refuse(partyId, session.getId(), "only the host controls playback");
                return;
            }
            registry.hostAction(partyId, frame.type(), frame.positionSeconds(),
                    identity.member().getDisplayName());
            return;
        }

        if (InboundFrame.REPORT.equals(frame.type())) {
            registry.report(partyId, session.getId(), frame.positionSeconds(), frame.buffering());
            return;
        }

        registry.refuse(partyId, session.getId(), "unknown frame type: " + frame.type());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.detach(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Watch party socket {} failed: {}", session.getId(), exception.toString());
        registry.detach(session);
        close(session);
    }

    private static SocketIdentity identityOf(WebSocketSession session) {
        Object identity = session.getAttributes().get(WatchPartyHandshakeInterceptor.IDENTITY);
        return identity instanceof SocketIdentity typed ? typed : null;
    }

    private static void close(WebSocketSession session) {
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ex) {
            log.debug("Close failed for {}: {}", session.getId(), ex.toString());
        }
    }
}
