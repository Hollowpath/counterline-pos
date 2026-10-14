package com.counterline.pos.outlet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneId;

/**
 * Settings shared by every outlet of the chain (the outlets themselves are data: {@link Outlet}).
 *
 * @param timeZone                the outlets' local time zone, for business dates (receipt codes, reports)
 * @param defaultPhoneCountryCode added to local phone numbers ("011…" → "+6011…")
 */
@Validated
@ConfigurationProperties("pos.outlet")
public record OutletProperties(
        @NotNull ZoneId timeZone,
        @NotBlank @Pattern(regexp = "[1-9]\\d{0,2}") String defaultPhoneCountryCode) {
}
