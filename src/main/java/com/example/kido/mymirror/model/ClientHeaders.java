package com.example.kido.mymirror.model;

/**
 * Headers taken from the app user's own request and passed on upstream, so the site sees
 * the caller's real device and language rather than one fixed browser. Any may be null.
 */
public record ClientHeaders(String userAgent, String accept, String acceptLanguage) {
}
