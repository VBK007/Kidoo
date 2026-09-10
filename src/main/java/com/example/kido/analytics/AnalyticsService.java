package com.example.kido.analytics;

import java.util.List;

import org.springframework.stereotype.Service;

import com.example.kido.analytics.dto.LogLoginEventRequest;
import com.example.kido.common.ClientAddress;
import com.example.kido.user.AppUser;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AnalyticsService {

    private final LoginEventRepository events;

    public AnalyticsService(LoginEventRepository events) {
        this.events = events;
    }

    public LoginEvent logLogin(AppUser user, LogLoginEventRequest req, HttpServletRequest request) {
        LoginEvent saved = events.save(LoginEvent.builder()
                .userId(user.getId())
                .deviceId(req.deviceId())
                .ip(ClientAddress.resolve(request))
                .country(header(request, "CF-IPCountry"))
                .region(header(request, "CF-Region"))
                .userAgent(header(request, "User-Agent"))
                .platform(req.platform())
                .appVersion(req.appVersion())
                .build());
        log.debug("Logged login user={} device={} ip={} country={}",
                user.getId(), req.deviceId(), saved.getIp(), saved.getCountry());
        return saved;
    }

    public List<LoginEvent> history(AppUser user) {
        return events.findByUserIdOrderByAtDesc(user.getId());
    }

    /** Null rather than blank: Cloudflare's geolocation headers are Enterprise-only and
     * simply absent otherwise, and an unset header must not become the string "null". */
    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value;
    }
}
