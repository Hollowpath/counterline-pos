package com.counterline.pos.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Secrets have no defaults in the base configuration, so a deployed environment that forgets
 * to supply them fails at startup instead of running with a known value.
 */
@Validated
@ConfigurationProperties("pos.security")
public record SecurityProperties(
        @NotBlank String pinPepper,
        @NotBlank @Size(min = 32, message = "must be at least 32 characters for HS256") String jwtSecret,
        @NotNull Duration tokenTtl,
        @NotNull Duration specialAccessTtl,
        @Valid @NotNull LoginThrottle loginThrottle) {

    public record LoginThrottle(@Min(1) int maxFailures, @NotNull Duration window) {
    }
}
