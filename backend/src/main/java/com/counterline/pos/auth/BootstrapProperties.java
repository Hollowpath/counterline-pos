package com.counterline.pos.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * First-run initialisation of a fresh, non-local database (see {@link BootstrapInitializer}).
 * Disabled by default. There are deliberately no defaults for any credential: when enabled, the
 * initializer rejects a missing or invalid value and names only the property, never its value.
 */
@ConfigurationProperties("pos.bootstrap")
public record BootstrapProperties(
        @DefaultValue("false") boolean enabled,
        String outletCode,
        String headOfficeName,
        String headOfficeEmail,
        String headOfficePassword,
        String managerName,
        String managerPin,
        String managerEmail,
        String managerPassword) {

    /** Never let credentials reach logs through an accidental toString(). */
    @Override
    public String toString() {
        return "BootstrapProperties[enabled=" + enabled + ", ...redacted]";
    }
}
