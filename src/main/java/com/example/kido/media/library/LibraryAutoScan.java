package com.example.kido.media.library;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the catalog in sync with the disk without anyone pressing a button.
 *
 * <p>Polls once a minute rather than binding {@code app.media.scan-interval-minutes}
 * straight into {@code @Scheduled}: that property defaults to 0 (disabled), and Spring
 * rejects a zero or negative fixed delay at startup. Polling is cheap — the common case
 * is an early return — so trading a little precision for a config value that is safe at
 * any setting, including the default, is worth it.
 */
@Slf4j
@Component
public class LibraryAutoScan implements ApplicationRunner {

    private final LibraryScanner scanner;
    private final MediaProperties props;

    private volatile Instant lastAutoScan = Instant.EPOCH;

    public LibraryAutoScan(LibraryScanner scanner, MediaProperties props) {
        this.scanner = scanner;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.isScanOnStartup()) {
            lastAutoScan = Instant.now();
            triggerScan("startup");
        }
    }

    @Scheduled(fixedDelayString = "PT1M")
    void poll() {
        int intervalMinutes = props.getScanIntervalMinutes();
        if (intervalMinutes <= 0) {
            return;
        }
        Instant due = lastAutoScan.plus(Duration.ofMinutes(intervalMinutes));
        if (Instant.now().isBefore(due)) {
            return;
        }
        lastAutoScan = Instant.now();
        triggerScan("scheduled");
    }

    private void triggerScan(String trigger) {
        try {
            scanner.startAsync();
            log.info("Auto library scan started ({})", trigger);
        } catch (ApiException ex) {
            // No libraries configured, or a scan (manual or auto) is already running —
            // either way there is nothing to do until the next tick.
            log.debug("Auto library scan skipped ({}): {}", trigger, ex.getMessage());
        }
    }
}
