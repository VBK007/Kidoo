package com.example.kido.media.together;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the one socket endpoint this application has.
 *
 * <p>Raw WebSocket rather than STOMP: the protocol here is four message types over one
 * channel, and a broker relay would add a message-broker abstraction, a subscription
 * model and a second wire format to debug, all to carry a number and a state.
 *
 * <p>Origins are left open, matching {@code SecurityConfig}, because the clients are
 * mobile apps and a self-hosted web player on whatever address the household reaches
 * this server by — there is no fixed origin to allow.
 */
@Configuration
@EnableWebSocket
public class WatchPartyWebSocketConfig implements WebSocketConfigurer {

    public static final String PATH = "/ws/party";

    private final WatchPartySocketHandler handler;
    private final WatchPartyHandshakeInterceptor interceptor;

    public WatchPartyWebSocketConfig(WatchPartySocketHandler handler,
                                     WatchPartyHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, PATH)
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns("*");
    }
}
