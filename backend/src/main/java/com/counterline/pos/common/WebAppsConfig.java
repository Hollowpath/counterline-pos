package com.counterline.pos.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * The web apps (POS, customer display, dashboard) are served from the image's static files (see
 * the Dockerfile). Vite names its assets by content hash, so they can be cached for a year; the
 * HTML pages are never cached (spring.web.resources.cache.cachecontrol), so a new release is
 * picked up on the next load.
 */
@Configuration
class WebAppsConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }
}
