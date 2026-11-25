package com.example.kido.mymirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class MirrorStreamSignerTest {
    @Test
    void acceptsAnUnexpiredPreviousKeyAndRejectsExpiredOrRetiredLinks() {
        String previousKey = fixtureKey((byte) 1);
        String activeKey = fixtureKey((byte) 2);
        Duration lifetime = Duration.ofMinutes(15);
        var previous = new MirrorStreamSigner(previousKey, "", lifetime);
        var rotated = new MirrorStreamSigner(activeKey, previousKey, lifetime);
        var retired = new MirrorStreamSigner(activeKey, "", lifetime);
        String payload = "https://origin.example\nhttps://cdn.example/variant.m3u8";
        String issued = previous.encodeLink(payload);
        String expired = previous.encode("0\n" + payload);

        assertEquals(payload, rotated.verify(issued, previous.sign(issued)));
        assertNull(retired.verify(issued, previous.sign(issued)));
        assertNull(rotated.verify(expired, previous.sign(expired)));
        String replacement = rotated.encodeLink(payload);
        assertEquals(payload, retired.verify(replacement, rotated.sign(replacement)));
    }

    private static String fixtureKey(byte seed) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, seed);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
