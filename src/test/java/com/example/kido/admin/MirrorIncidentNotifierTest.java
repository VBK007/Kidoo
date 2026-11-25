package com.example.kido.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.kido.mymirror.SessionRecoveryCoordinator.State;
import com.example.kido.mymirror.SessionRecoveryCoordinator.Status;

class MirrorIncidentNotifierTest {
    @Test
    void onlyInterventionStatesGenerateSanitizedAlerts() {
        assertEquals("OPERATOR_REQUIRED", MirrorIncidentNotifier.event(status(State.ACQUIRING)));
        assertEquals("RECOVERY_FAILED", MirrorIncidentNotifier.event(status(State.FAILED)));
        assertEquals("RECOVERY_TIMED_OUT", MirrorIncidentNotifier.event(status(State.TIMED_OUT)));
        assertNull(MirrorIncidentNotifier.event(status(State.SUCCEEDED)));
        assertNull(MirrorIncidentNotifier.event(status(State.VALIDATING)));
        String body = MirrorIncidentNotifier.payload(status(State.FAILED), "RECOVERY_FAILED");
        assertFalse(body.contains("test-only-private-diagnostic"));
        assertFalse(body.contains("processExit"));
    }

    private static Status status(State state) {
        return new Status(UUID.randomUUID(), UUID.randomUUID(), state, 1L, Instant.EPOCH,
                Instant.EPOCH, null, 5, "test-only-private-diagnostic");
    }
}
