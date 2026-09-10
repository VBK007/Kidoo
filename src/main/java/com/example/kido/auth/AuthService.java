package com.example.kido.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

import com.example.kido.auth.dto.AuthResponse;
import com.example.kido.auth.dto.FirebaseLoginRequest;
import com.example.kido.auth.dto.LoginRequest;
import com.example.kido.auth.dto.RegisterRequest;
import com.example.kido.auth.dto.UserDto;
import com.example.kido.common.ApiException;
import com.example.kido.security.JwtService;
import com.example.kido.user.AppUser;
import com.example.kido.user.Progress;
import com.example.kido.user.Role;
import com.example.kido.user.UserRepository;
import com.google.firebase.auth.FirebaseToken;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final FirebaseVerifier firebase;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       FirebaseVerifier firebase) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.firebase = firebase;
    }

    /**
     * Signs in with a token issued by Firebase, creating the account on first use.
     *
     * <p>Matched on email, so somebody who registered with a password and later taps
     * "Continue with Google" lands on the same account rather than a duplicate.
     *
     * <p>The stored password hash is deliberately a random value for a Google-created
     * account: the column is non-null, and leaving it blank or predictable would turn
     * the password login into a way in without a password.
     */
    public AuthResponse loginWithFirebase(FirebaseLoginRequest req) {
        FirebaseToken token = firebase.verify(req.idToken())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_IMPLEMENTED,
                        "This server is not configured for Google sign-in"));

        String email = token.getEmail();
        if (email == null || email.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "That Google account has no email address to identify it by");
        }
        final String key = email.trim().toLowerCase();

        AppUser user = userRepository.findByEmail(key).orElseGet(() -> {
            String displayName = token.getName() != null && !token.getName().isBlank()
                    ? token.getName().trim()
                    : key.substring(0, key.indexOf('@'));

            AppUser created = AppUser.builder()
                    .username(uniqueUsernameFrom(key))
                    .email(key)
                    .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                    .displayName(displayName)
                    .role(req.role() != null ? req.role() : Role.CHILD)
                    .progress(new Progress())
                    .build();

            AppUser saved = userRepository.save(created);
            log.info("Created account from Google sign-in id={} email='{}'", saved.getId(), key);
            return saved;
        });

        log.info("Google sign-in success for id={} email='{}'", user.getId(), key);
        return new AuthResponse(jwtService.generateToken(user), UserDto.from(user));
    }

    /** `arun@gmail.com` becomes `arun`, then `arun2`, `arun3` … if already taken. */
    private String uniqueUsernameFrom(String email) {
        String base = email.substring(0, email.indexOf('@'))
                .replaceAll("[^a-z0-9._-]", "");
        if (base.length() < 3) {
            base = "user" + base;
        }
        String candidate = base;
        int suffix = 2;
        while (userRepository.existsByUsername(candidate)) {
            candidate = base + suffix++;
        }
        return candidate;
    }

    public AuthResponse register(RegisterRequest req) {
        String username = req.username().trim().toLowerCase();
        String email = req.email().trim().toLowerCase();
        log.info("Registration attempt for username='{}'", username);

        if (userRepository.existsByUsername(username)) {
            log.warn("Registration rejected: username '{}' already taken", username);
            throw new ApiException(HttpStatus.CONFLICT, "Username is already taken");
        }
        if (userRepository.existsByEmail(email)) {
            log.warn("Registration rejected: email already registered");
            throw new ApiException(HttpStatus.CONFLICT, "Email is already registered");
        }

        AppUser user = AppUser.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(req.password()))
                .displayName(req.displayName().trim())
                .role(req.role() != null ? req.role() : Role.CHILD)
                .progress(new Progress())
                .build();

        AppUser saved = userRepository.save(user);
        log.info("Registered new user id={} username='{}' role={}", saved.getId(), username, saved.getRole());
        return new AuthResponse(jwtService.generateToken(saved), UserDto.from(saved));
    }

    public AuthResponse login(LoginRequest req) {
        String key = req.usernameOrEmail().trim().toLowerCase();
        log.info("Login attempt for '{}'", key);
        AppUser user = userRepository.findByUsernameOrEmail(key, key)
                .orElseThrow(() -> {
                    log.warn("Login failed: no user matching '{}'", key);
                    return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
                });

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            log.warn("Login failed: bad password for username='{}'", user.getUsername());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        log.info("Login success for username='{}' id={}", user.getUsername(), user.getId());
        return new AuthResponse(jwtService.generateToken(user), UserDto.from(user));
    }
}
