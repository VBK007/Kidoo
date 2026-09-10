package com.example.kido.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.example.kido.security.JwtAuthFilter;

@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Only registration and login are public; everything else
                        // (including /api/auth/me) requires a valid token.
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/firebase").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        // The watch party socket authenticates during its handshake:
                        // a browser cannot put a bearer token on a WebSocket, so the
                        // token arrives as a query parameter and the interceptor
                        // refuses the upgrade instead of the filter refusing a request.
                        .requestMatchers("/ws/party").permitAll()
                        // Content reads and the plans catalog are public so the app can
                        // fetch/cache offline-first before a parent signs in.
                        .requestMatchers(HttpMethod.GET, "/api/content/**", "/api/plans").permitAll()
                        // A guest asks for a seat and polls for the answer before they
                        // hold any credential at all, so these two are public. Neither
                        // works without a live join code, and neither hands out a token
                        // the host has not approved.
                        .requestMatchers(HttpMethod.POST, "/api/parties/*/guest").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/parties/*/guest/*").permitAll()
                        // What a watch party guest may reach, and the whole of it. Each
                        // of these re-checks, in its own handler, that the party is
                        // still live and the title is the one that guest was admitted
                        // to — the role here only says "a guest may knock at this
                        // door", never which room they end up in.
                        .requestMatchers(HttpMethod.POST, "/api/media/guest/playback-decision")
                        .hasRole("GUEST")
                        .requestMatchers(HttpMethod.GET, "/api/media/items/*/stream",
                                "/api/media/items/*/subtitles/*",
                                "/api/media/items/*/poster",
                                "/api/media/items/*/backdrop")
                        .hasAnyRole("CHILD", "PARENT", "GUEST")
                        .requestMatchers("/api/media/transcode/**")
                        .hasAnyRole("CHILD", "PARENT", "GUEST")
                        // Everything else needs an account, not merely a valid token.
                        // Every AppUser holds one of these two roles, so this is the
                        // same rule as authenticated() for real users — what it adds is
                        // that a watch party guest, who authenticates as a
                        // GuestPrincipal with no account behind it, is refused by
                        // default. Guests are let into individual endpoints above this
                        // line, each with its own check that the party is live and the
                        // title is the one they were admitted to.
                        //
                        // Default-deny matters here beyond permissions: handlers take
                        // an @AuthenticationPrincipal AppUser, which resolves to null
                        // for a guest, so reaching one at all is a 500 rather than a
                        // refusal.
                        .anyRequest().hasAnyRole("CHILD", "PARENT"))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
