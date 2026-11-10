package app.brewflow.security;

import app.brewflow.common.ApiErrorWriter;
import app.brewflow.common.ErrorCode;
import app.brewflow.common.RequestIdFilter;
import app.brewflow.staff.StaffRepository;
import app.brewflow.staff.StaffUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the server-side session to a staff account on every request. A session dies when the account is
 * inactive or its session epoch moved (deactivation, credential reset or change, role change).
 */
public class SessionAuthFilter extends OncePerRequestFilter {

    public static final String SESSION_USER = "bf.uid";
    public static final String SESSION_EPOCH = "bf.epoch";

    /** Section 2.1: the only routes open while must_change_credential is true. */
    private static final Set<String> OPEN_WHILE_CHANGE_REQUIRED = Set.of(
            "GET /api/v1/auth/csrf",
            "GET /api/v1/auth/me",
            "POST /api/v1/auth/credential",
            "POST /api/v1/auth/logout",
            "POST /api/v1/auth/login");

    private final StaffRepository staff;
    private final ApiErrorWriter errors;

    public SessionAuthFilter(StaffRepository staff, ApiErrorWriter errors) {
        this.staff = staff;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_USER) instanceof UUID userId) {
            Optional<StaffUser> user = staff.findById(userId);
            Object epoch = session.getAttribute(SESSION_EPOCH);
            if (user.isEmpty() || !user.get().active() || !(epoch instanceof Integer e) || e != user.get().sessionEpoch()) {
                session.invalidate();
            } else {
                StaffUser u = user.get();
                StaffPrincipal principal = new StaffPrincipal(u.id(), u.login(), u.displayName(), u.role(),
                        u.mustChangeCredential());
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + u.role().name()))));
                SecurityContextHolder.setContext(context);
                MDC.put(RequestIdFilter.MDC_ACTOR_ID, u.id().toString());

                if (u.mustChangeCredential()
                        && !OPEN_WHILE_CHANGE_REQUIRED.contains(request.getMethod() + " " + request.getRequestURI())) {
                    errors.write(response, ErrorCode.CREDENTIAL_CHANGE_REQUIRED,
                            "Change your temporary credential before continuing.");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
