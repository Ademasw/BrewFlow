package app.brewflow.common;

import org.springframework.http.HttpStatus;

/** Section 7 error codes. */
public enum ErrorCode {
    INVALID_INPUT(HttpStatus.UNPROCESSABLE_CONTENT),
    MODIFIER_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT),
    INVALID_QUANTITY(HttpStatus.UNPROCESSABLE_CONTENT),
    ATTESTATION_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),

    FORBIDDEN(HttpStatus.FORBIDDEN),
    CSRF_INVALID(HttpStatus.FORBIDDEN),
    CREDENTIAL_CHANGE_REQUIRED(HttpStatus.FORBIDDEN),

    NOT_FOUND(HttpStatus.NOT_FOUND),

    QUOTE_EXPIRED(HttpStatus.CONFLICT),
    QUOTE_STILL_VALID(HttpStatus.CONFLICT),
    QUOTE_ALREADY_CONSUMED(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
    INVALID_TRANSITION(HttpStatus.CONFLICT),
    VERSION_CONFLICT(HttpStatus.CONFLICT),
    SHIFT_CLOSED(HttpStatus.CONFLICT),
    MENU_CHANGED(HttpStatus.CONFLICT),
    MODE_REJECTED(HttpStatus.CONFLICT),
    AMOUNT_MISMATCH(HttpStatus.CONFLICT),
    ALREADY_VOIDED(HttpStatus.CONFLICT),
    LAST_ACTIVE_ADMIN(HttpStatus.CONFLICT),

    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
