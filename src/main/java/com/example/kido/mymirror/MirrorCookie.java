package com.example.kido.mymirror;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The caller's mirror session cookie, taken from the {@code X-Mirror-Cookie} header, or
 * from a {@code cookie} query parameter for callers that still send it that way. The header
 * is preferred: a query string is written to the request log, a header is not.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface MirrorCookie {

    String HEADER = "X-Mirror-Cookie";
    String PARAM = "cookie";
}
