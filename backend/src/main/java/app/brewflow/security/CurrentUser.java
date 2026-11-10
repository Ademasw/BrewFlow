package app.brewflow.security;

import app.brewflow.common.ApiException;
import app.brewflow.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static StaffPrincipal get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof StaffPrincipal principal) {
            return principal;
        }
        throw new ApiException(ErrorCode.UNAUTHENTICATED, "Sign in first.");
    }
}
