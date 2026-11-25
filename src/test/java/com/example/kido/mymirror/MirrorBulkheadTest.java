package com.example.kido.mymirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MirrorBulkheadTest {
    @Test
    void rejectsAtCapacityAndExpiresQueuedWorkWithoutRunningIt() throws Exception {
        var metrics = new SimpleMeterRegistry();
        try {
            var bulkhead = new MirrorBulkhead(1, 1, Duration.ofSeconds(5), Duration.ofMillis(500), metrics, new MirrorRequestLimit());
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var ran = new AtomicBoolean();
            try {
                bulkhead.submit(() -> {
                    entered.countDown();
                    release.await();
                    return "first";
                });
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var queued = bulkhead.submit(() -> {
                    ran.set(true);
                    return "queued";
                });
                assertEquals(1, metrics.get("mirror.executor.queue").tag("lane", "foreground").gauge().value());
                assertInstanceOf(MirrorRequestLimit.Busy.class, bulkhead.submit(() -> "excess").getResult());

                long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (!queued.hasResult() && System.nanoTime() < until) Thread.sleep(10);
                assertInstanceOf(MirrorRequestLimit.Busy.class, queued.getResult());
                assertEquals(0, metrics.get("mirror.executor.queue").tag("lane", "foreground").gauge().value());
                assertFalse(ran.get());
            } finally {
                release.countDown();
                bulkhead.stop();
            }
        } finally {
            metrics.close();
        }
    }
}
