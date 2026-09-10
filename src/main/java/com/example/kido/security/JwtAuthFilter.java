package com.example.kido.security;

import java.io.IOException;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.kido.user.AppUser;
import com.example.kido.user.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads a Bearer token, validates it, and populates the security context.
 *
 * <p>Two kinds of token arrive here. An account token names a user, and everything
 * downstream expects to find an {@code AppUser} as the principal. A watch party guest
 * token names nobody — there is no row to load — so it authenticates as a
 * {@link GuestPrincipal} instead.
 *
 * <p>This filter only decides <em>who</em> is calling. Whether a guest's party is still
 * running is an authorisation question, settled where it is acted on, because a party
 * routinely ends long before the token it issued would have expired.
 */
@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            String token = header.substring(BEARER_PREFIX.length());
            if (!authenticateGuest(token, request)) {
                authenticateAccount(token, request);
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Tried first, and cheaply: a guest token is recognised from its own claims without
     * touching the database, and an account token fails the check immediately.
     *
     * @return whether the token was a valid guest token
     */
    private boolean authenticateGuest(String token, HttpServletRequest request) {
        return jwtService.extractGuest(token).map(guest -> {
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(guest, null, guest.authorities());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
            log.debug("Authenticated guest '{}' of party {} for {} {}",
                    guest.displayName(), guest.partyId(),
                    request.getMethod(), request.getRequestURI());
            return true;
        }).orElse(false);
    }

    private void authenticateAccount(String token, HttpServletRequest request) {
        String userId = jwtService.extractUserId(token);
        if (userId == null) {
            log.warn("Rejected invalid/expired token for {} {}",
                    request.getMethod(), request.getRequestURI());
            return;
        }
        userRepository.findById(userId).ifPresentOrElse(user -> {
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(user, null, user.authorities());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
            log.debug("Authenticated username='{}' for {} {}", user.getUsername(),
                    request.getMethod(), request.getRequestURI());
        }, () -> log.warn("Token valid but user id={} not found", userId));
    }
}
