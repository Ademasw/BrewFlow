package app.brewflow.staff;

import app.brewflow.common.ApiException;
import java.util.regex.Pattern;

/** Section 8: admin password of at least 12 characters; staff PIN of six digits (candidate). */
public final class CredentialPolicy {

    public static final int ADMIN_MIN_LENGTH = 12;
    private static final int MAX_LENGTH = 128;
    private static final Pattern PIN = Pattern.compile("\\d{6}");

    private CredentialPolicy() {
    }

    public static void validate(Role role, String field, String credential) {
        if (credential == null || credential.isEmpty()) {
            throw ApiException.invalid(field, field + " is required.");
        }
        if (role == Role.ADMIN) {
            int length = credential.codePointCount(0, credential.length());
            if (length < ADMIN_MIN_LENGTH || length > MAX_LENGTH) {
                throw ApiException.invalid(field, "An admin password needs 12 to 128 characters.");
            }
            if (credential.isBlank()) {
                throw ApiException.invalid(field, "An admin password cannot be only spaces.");
            }
        } else if (!PIN.matcher(credential).matches()) {
            throw ApiException.invalid(field, "A staff PIN is exactly six digits.");
        }
    }
}
