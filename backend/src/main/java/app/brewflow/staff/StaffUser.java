package app.brewflow.staff;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StaffUser(
        UUID id,
        String login,
        String displayName,
        Role role,
        String credentialHash,
        boolean active,
        boolean mustChangeCredential,
        int failedAttempts,
        boolean locked,
        int sessionEpoch,
        int revision,
        OffsetDateTime createdAt) {

    public static String normalizeLogin(String login) {
        return login == null ? "" : login.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
