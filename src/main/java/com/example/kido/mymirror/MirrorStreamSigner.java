package com.example.kido.mymirror;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Signs the short-lived upstream stream URLs the HLS master hands back to the player.
 *
 * <p>The master's variant playlists sit on the mirror's segment CDN, which serves a playlist only
 * to a request carrying the mirror's {@code Referer} — a header the player cannot set and that the
 * mirror's live host, which rotates, decides. So the master rewrites each variant URL to this
 * server's {@code /playback/variant} proxy, which adds that Referer. A signature makes the proxy
 * fetch only a URL this server itself minted, rather than an open relay anyone could point at an
 * arbitrary path. The key is random per process: a restart simply invalidates in-flight stream
 * URLs, which the player recovers from by reloading — the mirror session is itself short-lived.
 */
@Component
public class MirrorStreamSigner {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] key = new byte[32];

    public MirrorStreamSigner() {
        new SecureRandom().nextBytes(key);
    }

    /** The payload as a URL-safe, unpadded base64 token — safe to carry in a query string. */
    public String encode(String payload) {
        return B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /** HMAC-SHA256 of an encoded token, itself URL-safe base64. */
    public String sign(String encoded) {
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
        byte[] expected = sign(encoded).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        try {
            return new String(B64D.decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
