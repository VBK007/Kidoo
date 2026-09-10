package com.example.kido.analytics.dto;

import java.time.Instant;

import com.example.kido.analytics.LoginEvent;

public record LoginEventDto(
        String id,
        String deviceId,
        String ip,
        String country,
        String region,
        String platform,
        String appVersion,
        Instant at
) {
    public static LoginEventDto from(LoginEvent e) {
        return new LoginEventDto(e.getId(), e.getDeviceId(), e.getIp(), e.getCountry(),
                e.getRegion(), e.getPlatform(), e.getAppVersion(), e.getAt());
    }
}
