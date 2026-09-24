package com.example.kido.mymirror;

import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.example.kido.common.ApiException;

@Configuration
public class MyMirrorConfig implements WebMvcConfigurer {

    /** Shared by the mymirror repositories. Timed out so a dead mirror cannot hang a request thread. */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(20));
        return new RestTemplate(factory);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new MirrorCookieResolver());
    }

    /** Resolves {@link MirrorCookie} parameters: header first, then the query parameter. */
    static final class MirrorCookieResolver implements HandlerMethodArgumentResolver {

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
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "The mirror session cookie is required (" + MirrorCookie.HEADER + " header)");
            }
            // A CR or LF would let the value add headers of its own to the upstream request.
            if (cookie.chars().anyMatch(c -> c == '\r' || c == '\n')) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The mirror session cookie is malformed");
            }
            return cookie.trim();
        }
    }
}
