package com.counterline.pos.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard reloads a catalogue screen when a change is refused with exactly this answer
 * (frontend/src/dashboard/catalogHelpers.ts, {@code COLLIDED}). Rewording it here without changing that constant would
 * silently stop the reload, so the exact text, status and title are pinned. The frontend test pins the same text on its
 * side. A violation that is not a collision stays a server error and does not carry it.
 */
class CatalogCollisionMessageContractTest {

    private final CatalogErrorHandler handler = new CatalogErrorHandler();

    @Test
    void aUniqueOrForeignKeyCollisionIsRefusedWithExactlyTheTextTheDashboardReloadsOn() {
        for (String state : new String[] {"23505", "23503"}) {
            ProblemDetail problem = handler.handleCollision(new DataIntegrityViolationException("collision",
                    new SQLException("violates a constraint", state)));

            assertThat(problem.getStatus()).as(state).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).as(state).isEqualTo("Conflict");
            assertThat(problem.getDetail()).as(state)
                    .isEqualTo("That change collided with another one, or with a sale. Reload and try again.");
        }
    }

    @Test
    void aCheckOrNotNullViolationIsAServerErrorWithoutThatText() {
        for (String state : new String[] {"23514", "23502"}) {
            ProblemDetail problem = handler.handleCollision(new DataIntegrityViolationException("defect",
                    new SQLException("violates a constraint", state)));

            assertThat(problem.getStatus()).as(state).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(problem.getDetail()).as(state).isEqualTo("The change could not be saved.");
        }
    }
}
