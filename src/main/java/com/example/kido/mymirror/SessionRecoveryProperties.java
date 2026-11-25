package com.example.kido.mymirror;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties("app.mymirror.recovery")
public class SessionRecoveryProperties {
    private boolean enabled;
    private String node = "/usr/bin/node";
    private Path script = Path.of("tools/netmirror-capture/acquire-session.mjs");
    private Path workingDirectory = Path.of(".");
    private Duration acquisitionTimeout = Duration.ofMinutes(5);
    private Duration validationTimeout = Duration.ofSeconds(10);
    private Duration failureCooldown = Duration.ofMinutes(1);
    private Path stagingDirectory;
    private Path metadataPath;
    private URI expiryEndpoint;
    private int expiryStatus;
    private String expiryHeader = "";
    private String expiryValue = "";
    private URI validationEndpoint;
    private String validationHeader = "";
    private String validationValue = "";
}
