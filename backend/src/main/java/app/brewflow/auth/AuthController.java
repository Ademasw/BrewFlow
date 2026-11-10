package app.brewflow.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import app.brewflow.auth.AuthService.LoginOutcome;
import app.brewflow.common.ApiException;
import app.brewflow.common.AppMode;
import app.brewflow.common.BrewFlowProperties;
import app.brewflow.common.ErrorCode;
import app.brewflow.common.TimeConfig.TimeSource;
import app.brewflow.kds.TicketStreamHub;
import app.brewflow.security.CurrentUser;
import app.brewflow.security.SessionAuthFilter;
import app.brewflow.security.StaffPrincipal;
import app.brewflow.staff.StaffUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Auth", description = "Session, CSRF and own credential")
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(@NotBlank @Size(max = 64) String login, @NotBlank @Size(max = 128) String credential) {
    }

    public record CredentialChangeRequest(@NotBlank @Size(max = 128) String currentCredential,
                                          @NotBlank @Size(max = 128) String newCredential) {
    }

    private final AuthService auth;
    private final LoginThrottle throttle;
    private final HttpSessionCsrfTokenRepository csrfRepository;
    private final TicketStreamHub streams;
    private final AppMode appMode;
    private final BrewFlowProperties props;
    private final TimeSource time;

    public AuthController(AuthService auth, LoginThrottle throttle, HttpSessionCsrfTokenRepository csrfRepository,
                          TicketStreamHub streams, AppMode appMode, BrewFlowProperties props, TimeSource time) {
        this.auth = auth;
        this.throttle = throttle;
        this.csrfRepository = csrfRepository;
        this.streams = streams;
        this.appMode = appMode;
        this.props = props;
        this.time = time;
    }

    @Operation(summary = "CSRF header name and token (starts a session if needed)")
    @GetMapping("/csrf")
    Map<String, String> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @Operation(summary = "Sign in: rotates the session and returns a new CSRF token; 401 or 429")
    @PostMapping("/login")
    Map<String, Object> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                              HttpServletResponse response) {
        String address = request.getRemoteAddr();
        if (throttle.isBlocked(address)) {
            throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS, "Too many failed sign-ins. Ask a manager.");
        }
        LoginOutcome outcome = auth.attemptLogin(body.login(), body.credential());
        switch (outcome.status()) {
            case LOCKED -> {
                throttle.recordFailure(address);
                throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS,
                        "This account is locked. A manager must reset the credential.");
            }
            case INVALID -> {
                throttle.recordFailure(address);
                throw new ApiException(ErrorCode.UNAUTHENTICATED, "Login or credential is wrong.");
            }
            case OK -> {
                // staff switch or fresh login: the old session and its streams end, a new session id is issued
                HttpSession old = request.getSession(false);
                if (old != null) {
                    streams.closeForSession(old.getId());
                    old.invalidate();
                }
                StaffUser user = outcome.user();
                HttpSession session = request.getSession(true);
                session.setAttribute(SessionAuthFilter.SESSION_USER, user.id());
                session.setAttribute(SessionAuthFilter.SESSION_EPOCH, user.sessionEpoch());
                CsrfToken token = csrfRepository.generateToken(request);
                csrfRepository.saveToken(token, request, response);

                Map<String, Object> result = me(new StaffPrincipal(user.id(), user.login(), user.displayName(),
                        user.role(), user.mustChangeCredential()), session);
                result.put("csrf", Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
                return result;
            }
            default -> throw new IllegalStateException();
        }
    }

    @Operation(summary = "Current identity, role, mustChangeCredential, appMode")
    @GetMapping("/me")
    Map<String, Object> me(HttpServletRequest request) {
        return me(CurrentUser.get(), request.getSession(false));
    }

    @Operation(summary = "Change own PIN or password; clears mustChangeCredential and rotates the session")
    @PostMapping("/credential")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changeCredential(@Valid @RequestBody CredentialChangeRequest body, HttpServletRequest request) {
        StaffPrincipal user = CurrentUser.get();
        int epoch = auth.changeOwnCredential(user.id(), body.currentCredential(), body.newCredential());
        request.changeSessionId();
        request.getSession().setAttribute(SessionAuthFilter.SESSION_EPOCH, epoch);
    }

    @Operation(summary = "Sign out: the session and its KDS streams end")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        StaffPrincipal user = CurrentUser.get();
        HttpSession session = request.getSession(false);
        if (session != null) {
            streams.closeForSession(session.getId());
            session.invalidate();
        }
        auth.recordLogout(user.id());
    }

    private Map<String, Object> me(StaffPrincipal user, HttpSession session) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.id());
        m.put("login", user.login());
        m.put("displayName", user.displayName());
        m.put("role", user.role());
        m.put("mustChangeCredential", user.mustChangeCredential());
        m.put("idleTimeoutSeconds", session == null ? null : session.getMaxInactiveInterval());
        m.put("appMode", appMode.mode());
        m.put("currency", props.currency());
        // the site timezone decides the business date; clients do not guess it
        m.put("businessDate", time.today());
        return m;
    }
}
