package com.counterline.pos.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Problem-detail responses for authentication and authorization failures. */
@RestControllerAdvice
class AuthExceptionHandler {

    @ExceptionHandler(InvalidPinException.class)
    ProblemDetail handleInvalidPin(InvalidPinException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Login failed", ex.getMessage());
    }

    @ExceptionHandler(SpecialAccessDeniedException.class)
    ProblemDetail handleSpecialAccessDenied(SpecialAccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Special access required", ex.getMessage());
    }

    @ExceptionHandler(TillNotSetUpException.class)
    ProblemDetail handleTillNotSetUp(TillNotSetUpException ex) {
        return problem(HttpStatus.GONE, "iPad not set up", ex.getMessage());
    }

    @ExceptionHandler(TooManyLoginAttemptsException.class)
    ResponseEntity<ProblemDetail> handleTooManyAttempts(TooManyLoginAttemptsException ex) {
        long seconds = ex.retryAfterSeconds();
        ProblemDetail body = problem(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts",
                "Too many incorrect PINs. Try again in " + seconds + " seconds"
                        + (ex.managerCanUnlock() ? ", or ask a manager to unlock it on the dashboard." : "."));
        // For a countdown on the PIN pad.
        body.setProperty("retryAfterSeconds", seconds);
        body.setProperty("managerCanUnlock", ex.managerCanUnlock());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .body(body);
    }

    /** Missing, expired or invalid token (or a deactivated account). */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleUnauthenticated(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem(HttpStatus.UNAUTHORIZED, "Authentication required", "Log in to continue."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Access denied", "Your role does not allow this action.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
