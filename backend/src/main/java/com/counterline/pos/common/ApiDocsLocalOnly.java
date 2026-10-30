package com.counterline.pos.common;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * The API documentation (the OpenAPI document and Swagger UI) is for local development only: it is
 * enabled by the {@code local} profile and off everywhere else. Should it be switched on in any
 * other setting (a stray property or environment variable), the application refuses to start rather
 * than serve its route map to anyone who can reach it.
 */
@Component
class ApiDocsLocalOnly implements InitializingBean {

    static final String API_DOCS = "springdoc.api-docs.enabled";
    static final String SWAGGER_UI = "springdoc.swagger-ui.enabled";

    private final Environment environment;

    ApiDocsLocalOnly(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        Binder binder = Binder.get(environment);
        // Unset counts as off (application.yml sets both to false), though springdoc itself would default to on.
        boolean enabled = binder.bind(API_DOCS, Boolean.class).orElse(false)
                || binder.bind(SWAGGER_UI, Boolean.class).orElse(false);
        if (enabled && !environment.acceptsProfiles(Profiles.of("local"))) {
            throw new IllegalStateException("The API documentation (" + API_DOCS + ", " + SWAGGER_UI
                    + ") may be enabled only in the local profile");
        }
    }
}
