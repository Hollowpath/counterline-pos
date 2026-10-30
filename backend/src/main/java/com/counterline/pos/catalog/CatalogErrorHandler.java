package com.counterline.pos.catalog;

import com.counterline.pos.common.DatabaseConflicts;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A catalogue change that still collides with something (a name or position taken at the same moment, or an item
 * a sale has just committed: a unique or foreign-key violation) is a conflict to retry after a reload, not a server
 * error. Any other integrity violation (a check or not-null constraint) is a defect and stays a server error.
 */
@RestControllerAdvice(basePackages = "com.counterline.pos.catalog")
class CatalogErrorHandler {

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleCollision(DataIntegrityViolationException ex) {
        if (!DatabaseConflicts.isCollision(ex)) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "The change could not be saved.");
            problem.setTitle("Server error");
            return problem;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "That change collided with another one, or with a sale. Reload and try again.");
        problem.setTitle("Conflict");
        return problem;
    }
}
