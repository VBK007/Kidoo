package com.example.kido.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.auth.dto.AuthResponse;
import com.example.kido.auth.dto.FirebaseLoginRequest;
import com.example.kido.auth.dto.LoginRequest;
import com.example.kido.auth.dto.RefreshRequest;
import com.example.kido.auth.dto.RegisterRequest;
import com.example.kido.auth.dto.UserDto;
import com.example.kido.user.AppUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * Signs in with a Firebase ID token and returns this server's own JWT.
     *
     * <p>The client authenticates against Google, not against us; we verify the
     * resulting token and issue the same {@link AuthResponse} the password login
     * returns, so everything downstream — profiles, media, the admin panel — is
     * unchanged and unaware of how the session began.
     */
    @PostMapping("/firebase")
    public ResponseEntity<AuthResponse> firebase(
            @Valid @RequestBody FirebaseLoginRequest request) {
        return ResponseEntity.ok(authService.loginWithFirebase(request));
    }

    /**
     * Trades a refresh token for a new access token, no password involved.
     *
     * <p>Public, like login, because the access token that would authenticate the call
     * is exactly the thing that has expired by the time a client needs this. The refresh
     * token in the body is the credential.
     *
     * <p>The response carries a <em>new</em> refresh token as well: the one just sent is
     * spent and will not work twice. Clients must overwrite what they stored.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> me(@AuthenticationPrincipal AppUser user) {
        return ResponseEntity.ok(UserDto.from(user));
    }
}
