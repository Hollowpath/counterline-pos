package com.counterline.pos.orders;

import com.counterline.pos.outlet.OutletProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Receipt codes: the outlet's local date (yyMMdd) followed by seven
 * random letters, e.g. "260912SSZGJLH". Hard to guess, so a receipt cannot be used to
 * enumerate other orders; uniqueness is enforced by the database.
 */
@Component
class ReceiptCodeGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final int RANDOM_LENGTH = 7;

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final OutletProperties outlet;

    ReceiptCodeGenerator(Clock clock, OutletProperties outlet) {
        this.clock = clock;
        this.outlet = outlet;
    }

    String next() {
        StringBuilder code = new StringBuilder(LocalDate.now(clock.withZone(outlet.timeZone())).format(DATE));
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            code.append((char) ('A' + random.nextInt(26)));
        }
        return code.toString();
    }
}
