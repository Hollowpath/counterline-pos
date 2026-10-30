package com.counterline.pos.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The API documentation may be on in the local profile only; elsewhere the application will not start with it on. */
class ApiDocsLocalOnlyTest {

    private static void start(MockEnvironment environment) {
        new ApiDocsLocalOnly(environment).afterPropertiesSet();
    }

    @Test
    void startsWithTheDocumentationOffInAnyProfile() {
        assertThatCode(() -> start(new MockEnvironment())).doesNotThrowAnyException();
        assertThatCode(() -> start(env("prod").withProperty("springdoc.api-docs.enabled", "false")
                .withProperty("springdoc.swagger-ui.enabled", "false"))).doesNotThrowAnyException();
    }

    @Test
    void startsWithTheDocumentationOnInTheLocalProfile() {
        assertThatCode(() -> start(env("local").withProperty("springdoc.api-docs.enabled", "true")
                .withProperty("springdoc.swagger-ui.enabled", "true"))).doesNotThrowAnyException();
    }

    @Test
    void refusesToStartWithEitherPartOnOutsideTheLocalProfile() {
        for (String property : new String[] {"springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled"}) {
            for (String profile : new String[] {null, "prod", "test", "aws"}) {
                MockEnvironment environment = profile == null ? new MockEnvironment() : env(profile);
                assertThatThrownBy(() -> start(environment.withProperty(property, "true")))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("only in the local profile");
            }
        }
    }

    @Test
    void refusesWhenItIsSwitchedOnThroughTheEnvironmentToo() {
        // Spring's relaxed binding: SPRINGDOC_APIDOCS_ENABLED=true.
        MockEnvironment environment = env("prod");
        environment.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                "systemEnvironment", java.util.Map.of("SPRINGDOC_APIDOCS_ENABLED", "true")));
        assertThatThrownBy(() -> start(environment)).isInstanceOf(IllegalStateException.class);
    }

    private static MockEnvironment env(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
