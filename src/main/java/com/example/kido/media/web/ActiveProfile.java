package com.example.kido.media.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the profile the request is acting as, taken from the {@code X-Profile-Id}
 * header and validated against the authenticated account.
 *
 * <p>Watch state belongs to a profile rather than an account: a household shares one
 * login but each person needs their own resume points and continue-watching row.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ActiveProfile {
}
