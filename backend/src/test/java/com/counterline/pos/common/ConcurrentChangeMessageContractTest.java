package com.counterline.pos.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard reloads a catalogue screen when it is refused with exactly this answer (frontend/src/dashboard/catalogHelpers.ts,
 * {@code CHANGED_ELSEWHERE}). Rewording it here without changing that constant would silently stop the reload, so the exact
 * text, status and title are pinned. The frontend test pins the same text on its side.
 */
class ConcurrentChangeMessageContractTest {

    @Test
    void anEditMadeFromAnOlderVersionIsRefusedWithExactlyTheTextTheDashboardReloadsOn() {
        ProblemDetail problem = new ApiExceptionHandler()
                .handleConcurrentChange(new ObjectOptimisticLockingFailureException(Object.class, 1L));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Conflicting change");
        assertThat(problem.getDetail()).isEqualTo("Changed elsewhere. Reload.");
    }

    @Test
    void aBusyAnswerIsAnotherConflictThatMustNotBeTakenForIt() {
        ProblemDetail problem = new ApiExceptionHandler()
                .handleLockFailure(new org.springframework.dao.CannotAcquireLockException("busy"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail()).isEqualTo("Busy. Retry.").isNotEqualTo("Changed elsewhere. Reload.");
    }
}
