package app.brewflow.security;

import app.brewflow.staff.Role;
import java.util.UUID;

public record StaffPrincipal(UUID id, String login, String displayName, Role role, boolean mustChangeCredential) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
