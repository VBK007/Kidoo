package com.example.kido.mymirror;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.example.kido.common.ApiException;

@Configuration
public class MyMirrorConfig implements WebMvcConfigurer {

    private final MirrorSessionProvider sessionProvider;

    public MyMirrorConfig(MirrorSessionProvider sessionProvider) {
        this.sessionProvider = sessionProvider;
    }

    // The shared upstream RestTemplate now lives on MirrorHttpConfig, not here: this class injects
    // MirrorSessionProvider for its cookie resolver, and that provider transitively needs the
    // RestTemplate, so hosting the bean here closed a context-breaking cycle.

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new MirrorCookieResolver(sessionProvider));
    }

    /**
     * Resolves {@link MirrorCookie} parameters. A caller that carries its own fresher session wins:
     * header first, then the query parameter. When it carries none — the normal path now that the app
     * no longer pastes a cookie — it falls back to the backend's one global mirror session
     * ({@link MirrorSessionProvider}). If no global session is held, it asks the provider to earn one
     * (which opens the ad gate for a human to clear) and answers 503 so the caller retries once the
     * fresh session is in place.
     */
    static final class MirrorCookieResolver implements HandlerMethodArgumentResolver {

        private final MirrorSessionProvider sessionProvider;

        MirrorCookieResolver(MirrorSessionProvider sessionProvider) {
            this.sessionProvider = sessionProvider;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(MirrorCookie.class)
                    && String.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                      NativeWebRequest request, WebDataBinderFactory binderFactory) {
            String cookie = request.getHeader(MirrorCookie.HEADER);
            if (cookie == null || cookie.isBlank()) {
                cookie = request.getParameter(MirrorCookie.PARAM);
            }
            if (cookie == null || cookie.isBlank()) {
                // No caller cookie: use the backend's global session, earning one if none is held.
                cookie = sessionProvider.ensureSession();
            }
            if (cookie == null || cookie.isBlank()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        sessionProvider.unavailableReason());
            }
            // A CR or LF would let the value add headers of its own to the upstream request.
            if (cookie.chars().anyMatch(c -> c == '\r' || c == '\n')) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The mirror session cookie is malformed");
            }
            // The catalogue the caller is browsing rides on this request's cookie only.
            return MirrorOtt.apply(cookie.trim(), request.getHeader(MirrorOtt.HEADER));
        }
    }
}
