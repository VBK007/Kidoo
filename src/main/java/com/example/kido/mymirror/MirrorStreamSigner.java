package com.example.kido.mymirror;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** Issues time-limited bearer links for approved rendition URLs. */
@Component
public class MirrorStreamSigner {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] key;
    private final List<byte[]> verificationKeys;
    private final Duration lifetime;

    public MirrorStreamSigner() {
        this("", "", Duration.ofMinutes(15));
    }

    @Autowired
    public MirrorStreamSigner(@Value("${app.mymirror.variant.signing-key:}") String active,
                              @Value("${app.mymirror.variant.verification-keys:}") String previous,
                              @Value("${app.mymirror.variant.link-lifetime:15m}") Duration lifetime) {
        if (lifetime.toSeconds() < 1) throw new IllegalArgumentException("Mirror link lifetime must be positive");
        this.lifetime = lifetime;
        if (active.isBlank()) {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
        } else {
            key = readKey(active);
        }
        List<byte[]> keys = new ArrayList<>();
        keys.add(key);
        for (String value : previous.split(",")) {
            if (!value.isBlank()) keys.add(readKey(value));
        }
        verificationKeys = List.copyOf(keys);
    }

    private static byte[] readKey(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value.trim());
            if (decoded.length >= 32) return decoded;
        } catch (IllegalArgumentException ignored) {
            // Do not include the configured secret in configuration errors.
        }
        throw new IllegalArgumentException("Mirror signing keys must be base64 with at least 32 decoded bytes");
    }

    public String encodeLink(String payload) {
        return encode(Instant.now().plus(lifetime).getEpochSecond() + "\n" + payload);
    }

    /** The payload as a URL-safe, unpadded base64 token — safe to carry in a query string. */
    public String encode(String payload) {
        return B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /** HMAC-SHA256 of an encoded token, itself URL-safe base64. */
    public String sign(String encoded) {
        return sign(encoded, key);
    }

    private static String sign(String encoded, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    /** The decoded payload when the signature matches (constant-time), or {@code null} otherwise. */
    public String verify(String encoded, String signature) {
        if (encoded == null || signature == null) {
            return null;
        }
        byte[] given = signature.getBytes(StandardCharsets.US_ASCII);
        boolean valid = false;
        for (byte[] verifier : verificationKeys) {
            valid |= MessageDigest.isEqual(sign(encoded, verifier).getBytes(StandardCharsets.US_ASCII), given);
        }
        if (!valid) return null;
        try {
            String decoded = new String(B64D.decode(encoded), StandardCharsets.UTF_8);
            int end = decoded.indexOf('\n');
            if (end < 0 || Long.parseLong(decoded.substring(0, end)) <= Instant.now().getEpochSecond()) return null;
            return decoded.substring(end + 1);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
