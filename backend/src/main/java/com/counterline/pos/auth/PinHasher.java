package com.counterline.pos.auth;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/** Keyed, deterministic PIN hash (HMAC-SHA256). See V2__staff.sql for why this is not bcrypt. */
@Component
public class PinHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    PinHasher(SecurityProperties properties) {
        this.key = new SecretKeySpec(properties.pinPepper().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String hash(String pin) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(pin.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        }
    }
}
