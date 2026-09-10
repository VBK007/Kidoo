package com.example.kido.auth;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.example.kido.common.ApiException;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Verifies a Firebase ID token and nothing else.
 *
 * <p>Sign-in happens on the phone against Google; this server's only job is to check
 * that the token it is handed was really issued by the configured Firebase project and
 * has not expired. Everything after that — the session, the profile, every media
 * request — uses this server's own JWT, so a household that never configures Firebase
 * is completely unaffected.
 *
 * <p>Deliberately optional. {@code app.firebase.credentials} unset means the feature is
 * off and {@code /api/auth/firebase} answers 501 rather than the app failing to start:
 * a media server on a home LAN should not require a Google project to boot.
 */
@Slf4j
@Component
public class FirebaseVerifier {

    private static final String APP_NAME = "kido-auth";

    private final String credentials;
    private volatile FirebaseAuth auth;

    public FirebaseVerifier(
            @Value("${app.firebase.credentials:}") String credentials) {
        this.credentials = credentials == null ? "" : credentials.trim();
    }

    /**
     * Initialises at startup so a broken service-account file is a loud log line
     * on boot rather than a confusing 500 the first time somebody signs in.
     */
    @PostConstruct
    void init() {
        if (credentials.isBlank()) {
            log.info("Firebase sign-in disabled (app.firebase.credentials not set)");
            return;
        }
        try (InputStream stream = openCredentials()) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(stream))
                    .build();

            FirebaseApp app = FirebaseApp.getApps().stream()
                    .filter(existing -> APP_NAME.equals(existing.getName()))
                    .findFirst()
                    .orElseGet(() -> FirebaseApp.initializeApp(options, APP_NAME));

            this.auth = FirebaseAuth.getInstance(app);
            log.info("Firebase sign-in enabled");
        } catch (Exception ex) {
            // Not fatal: the rest of the server is unrelated to Firebase.
            log.error("Firebase sign-in could not be initialised: {}", ex.getMessage());
        }
    }

    public boolean isEnabled() {
        return auth != null;
    }

    /**
     * @return the verified token, or empty when the feature is off
     * @throws ApiException when the token is present but not valid
     */
    public Optional<FirebaseToken> verify(String idToken) {
        FirebaseAuth current = auth;
        if (current == null) {
            return Optional.empty();
        }
        try {
            // checkRevoked=true so signing out everywhere on a lost phone
            // actually ends the session rather than waiting for expiry.
            return Optional.of(current.verifyIdToken(idToken, true));
        } catch (FirebaseAuthException ex) {
            log.warn("Rejected Firebase ID token: {}", ex.getMessage());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "That Google sign-in was not accepted");
        }
    }

    /** Accepts either a path to the service-account file or its JSON inline. */
    private InputStream openCredentials() throws IOException {
        if (credentials.startsWith("{")) {
            return new ByteArrayInputStream(credentials.getBytes(StandardCharsets.UTF_8));
        }
        return Files.newInputStream(Path.of(credentials));
    }
}
