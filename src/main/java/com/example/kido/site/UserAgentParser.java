package com.example.kido.site;

import java.util.regex.Pattern;

/**
 * Just enough user-agent sniffing for an admin traffic breakdown — device type,
 * OS and browser family. Not a general-purpose parser: order matters below (a
 * Chrome UA also contains "Safari", an Edge UA also contains "Chrome"), and
 * anything not recognised falls back to a plain label rather than null, since
 * "Other" groups sensibly in the admin dashboard's top-N tables while null
 * would not.
 */
final class UserAgentParser {

    private static final Pattern TABLET = Pattern.compile("iPad|Tablet(?!.*Mobile)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOBILE = Pattern.compile("Mobi|iPhone|Android", Pattern.CASE_INSENSITIVE);

    private UserAgentParser() {}

    static String deviceType(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown";
        }
        if (TABLET.matcher(userAgent).find()) {
            return "Tablet";
        }
        if (MOBILE.matcher(userAgent).find()) {
            return "Mobile";
        }
        return "Desktop";
    }

    static String os(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown";
        }
        if (userAgent.contains("Windows")) {
            return "Windows";
        }
        if (userAgent.contains("Android")) {
            return "Android";
        }
        if (userAgent.contains("iPhone") || userAgent.contains("iPad") || userAgent.contains("iOS")) {
            return "iOS";
        }
        // Must come after Android/iOS: "Mac OS X" also appears in iOS WebKit UAs.
        if (userAgent.contains("Macintosh") || userAgent.contains("Mac OS X")) {
            return "macOS";
        }
        if (userAgent.contains("Linux")) {
            return "Linux";
        }
        return "Other";
    }

    static String browser(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown";
        }
        // Order matters: Edge and Opera UAs also contain "Chrome", and every
        // non-Safari browser on iOS/macOS still carries a "Safari" token.
        if (userAgent.contains("Edg/") || userAgent.contains("EdgA/") || userAgent.contains("EdgiOS/")) {
            return "Edge";
        }
        if (userAgent.contains("OPR/") || userAgent.contains("Opera")) {
            return "Opera";
        }
        if (userAgent.contains("SamsungBrowser/")) {
            return "Samsung Internet";
        }
        if (userAgent.contains("Firefox/") || userAgent.contains("FxiOS/")) {
            return "Firefox";
        }
        if (userAgent.contains("CriOS/") || userAgent.contains("Chrome/")) {
            return "Chrome";
        }
        if (userAgent.contains("Safari/")) {
            return "Safari";
        }
        return "Other";
    }
}
