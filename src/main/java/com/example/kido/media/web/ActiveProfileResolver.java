package com.example.kido.media.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.example.kido.common.ApiException;
import com.example.kido.profile.Profile;
import com.example.kido.profile.ProfileRepository;
import com.example.kido.user.AppUser;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves {@link ActiveProfile} parameters from the {@code X-Profile-Id} header.
 *
 * <p>Ownership is re-checked on every request via {@code findByIdAndOwnerId}, so a
 * caller cannot read another household's watch history by guessing a profile id — the
 * JWT establishes the account, the header only selects among that account's profiles.
 *
 * <p>With no header the account's first profile is used. The client always has an active
 * profile, but defaulting keeps single-person setups and manual API calls workable.
 */
@Slf4j
@Component
public class ActiveProfileResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-Profile-Id";

    private final ProfileRepository profiles;

    public ActiveProfileResolver(ProfileRepository profiles) {
        this.profiles = profiles;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(ActiveProfile.class)
                && Profile.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest request,
                                  WebDataBinderFactory binderFactory) {

        AppUser user = currentUser();
        String requested = request.getHeader(HEADER);

        if (requested != null && !requested.isBlank()) {
            return profiles.findByIdAndOwnerId(requested.trim(), user.getId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "Profile not found for this account"));
        }

        return profiles.findByOwnerIdOrderByCreatedAtAsc(user.getId()).stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "This account has no profiles; create one before browsing"));
    }

    private static AppUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUser user)) {
            // Endpoints taking a profile are all behind the JWT filter, so this means
            // a routing mistake rather than an anonymous caller.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return user;
    }
}
