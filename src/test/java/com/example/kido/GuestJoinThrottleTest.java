package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.example.kido.media.together.GuestJoinThrottle;

/**
 * The throttle in isolation.
 *
 * <p>Tested here rather than through the API because it is per address and the whole
 * integration suite shares one — a limit low enough to assert against would throttle
 * the other tests, and one high enough not to would never trip.
 */
class GuestJoinThrottleTest {

    @Test
    void allowsTheAllowanceThenRefuses() {
        GuestJoinThrottle throttle = new GuestJoinThrottle(3);

        assertTrue(throttle.allow("10.0.0.1"));
        assertTrue(throttle.allow("10.0.0.1"));
        assertTrue(throttle.allow("10.0.0.1"));
        assertFalse(throttle.allow("10.0.0.1"));
        assertFalse(throttle.allow("10.0.0.1"));
    }

    @Test
    void countsEachAddressSeparately() {
        GuestJoinThrottle throttle = new GuestJoinThrottle(2);

        assertTrue(throttle.allow("10.0.0.1"));
        assertTrue(throttle.allow("10.0.0.1"));
        assertFalse(throttle.allow("10.0.0.1"));

        // One noisy household must not lock out everyone else behind another address.
        assertTrue(throttle.allow("10.0.0.2"));
    }

    @Test
    void anUnknownAddressIsNeverRefused() {
        GuestJoinThrottle throttle = new GuestJoinThrottle(1);

        // Better to let an unattributable request through than to refuse every caller
        // the moment a proxy stops setting the header.
        assertTrue(throttle.allow(null));
        assertTrue(throttle.allow(""));
        assertTrue(throttle.allow(null));
    }
}
