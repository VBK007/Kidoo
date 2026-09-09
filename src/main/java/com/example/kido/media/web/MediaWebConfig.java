package com.example.kido.media.web;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the media module's argument resolvers.
 *
 * <p>Purely additive — it contributes one resolver and changes no existing MVC
 * behaviour, so the rest of the app is unaffected.
 */
@Configuration
public class MediaWebConfig implements WebMvcConfigurer {

    private final ActiveProfileResolver activeProfileResolver;

    public MediaWebConfig(ActiveProfileResolver activeProfileResolver) {
        this.activeProfileResolver = activeProfileResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(activeProfileResolver);
    }
}
