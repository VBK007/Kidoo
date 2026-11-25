package com.example.kido.mymirror;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/** Credentials and exact destinations approved by the operator for upstream requests. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.mymirror.access")
public class MirrorAccessProperties {
    /** Exact origins approved by the operator for sending mirror credentials. */
    private List<String> approvedOrigins = List.of("https://net52.cc", "https://net77.cc");

    public boolean isApproved(URI target) {
        return approvedOrigins.stream().anyMatch(origin -> UpstreamUrl.sameOrigin(target, origin));
    }

    /** Whether the credentialed client can attach configured clearance. */
    private boolean enabled = true;

    /** Optional operator-provisioned clearance for approved origins. */
    private String cfClearance = "";

    private String browserUserAgent = "";

    /** Inspect one credential-free redirect and accept only a configured destination. */
    private boolean followRedirects = false;

    /** Enable only when the provider documents the status as definitive credential revocation. */
    private int revocationStatus = 0;
}
