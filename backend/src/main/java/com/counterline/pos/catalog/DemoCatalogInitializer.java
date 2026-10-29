package com.counterline.pos.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads the demo catalogue (folders, tabs, items, gift-box options, promotions, discounts) into a
 * non-local database by running {@code db/demo/R__demo_catalog.sql} unchanged: that file is the one
 * source of the data, and the {@code local} profile loads the same file through Flyway. It creates no
 * outlets, accounts, staff, customers, sales or vouchers.
 *
 * <p>Enabled only by {@code pos.demo-catalog.enabled} (POS_DEMO_CATALOG_ENABLED), for a temporary
 * run: the script is idempotent but reshapes the catalogue to match itself (folders and items it does
 * not list are taken off the till and deactivated, listed prices are reset), so switch it off again
 * once the catalogue is loaded. It runs in one transaction after Flyway: if any statement fails,
 * nothing is changed and start-up stops.
 */
@Component
@ConditionalOnProperty(prefix = "pos.demo-catalog", name = "enabled", havingValue = "true")
class DemoCatalogInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoCatalogInitializer.class);

    static final String SCRIPT = "db/demo/R__demo_catalog.sql";

    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final Resource script;

    @Autowired
    DemoCatalogInitializer(JdbcTemplate jdbc, Environment environment) {
        this(jdbc, environment, new ClassPathResource(SCRIPT));
    }

    /** For tests only: a different script, to prove a failing one is rolled back. */
    DemoCatalogInitializer(JdbcTemplate jdbc, Environment environment, Resource script) {
        this.jdbc = jdbc;
        this.environment = environment;
        this.script = script;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (environment.acceptsProfiles(Profiles.of("local"))) {
            throw new IllegalStateException(
                    "Demo catalogue refused: pos.demo-catalog.enabled cannot be used with the local profile, "
                            + "which already loads the demo data");
        }
        String sql;
        try {
            sql = script.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Demo catalogue refused: the demo catalogue script cannot be read", e);
        }
        try {
            // One multi-statement execution on the transaction's connection; its temporary tables
            // (ON COMMIT DROP) and every change commit or roll back together.
            jdbc.execute(sql);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Demo catalogue refused: loading the script failed and nothing was changed", e);
        }
        log.info("Demo catalogue loaded from {}", SCRIPT);
    }
}
