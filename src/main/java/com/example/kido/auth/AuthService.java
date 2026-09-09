package com.example.kido.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.kido.auth.dto.AuthResponse;
import com.example.kido.auth.dto.LoginRequest;
import com.example.kido.auth.dto.RegisterRequest;
import com.example.kido.auth.dto.UserDto;
import com.example.kido.common.ApiException;
import com.example.kido.security.JwtService;
import com.example.kido.user.AppUser;
import com.example.kido.user.Progress;
import com.example.kido.user.Role;
import com.example.kido.user.UserRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
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
