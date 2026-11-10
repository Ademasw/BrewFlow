package app.brewflow.security;

import app.brewflow.common.ApiErrorWriter;
import app.brewflow.common.ErrorCode;
import app.brewflow.staff.StaffRepository;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;

/**
 * Section 2: one role rule per route, enforced on the backend for every request.
 * Ownership rules (a cashier's own orders and keys, 404 otherwise) live in the services.
 */
@Configuration
@ConditionalOnWebApplication
public class SecurityConfig {

    private static final String ADMIN = "ADMIN";
    private static final String CASHIER = "CASHIER";
    private static final String BARISTA = "BARISTA";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, StaffRepository staff, ApiErrorWriter errors,
                                    HttpSessionCsrfTokenRepository csrfRepository) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .addFilterBefore(new SessionAuthFilter(staff, errors), AnonymousAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                errors.write(response, ErrorCode.UNAUTHENTICATED, "Sign in first."))
                        .accessDeniedHandler((request, response, e) -> {
                            if (e instanceof CsrfException) {
                                errors.write(response, ErrorCode.CSRF_INVALID, "Missing or invalid CSRF token.");
                            } else {
                                errors.write(response, ErrorCode.FORBIDDEN, "Your role cannot do this.");
                            }
                        }))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // API documentation only; turned off with SWAGGER_ENABLED=false
                        .requestMatchers(GET, "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**",
                                "/v3/api-docs.yaml").permitAll()

                        .requestMatchers(GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(POST, "/api/v1/auth/credential", "/api/v1/auth/logout").authenticated()

                        .requestMatchers(GET, "/api/v1/menu").hasAnyRole(CASHIER, ADMIN)
                        .requestMatchers(POST, "/api/v1/orders/quotes", "/api/v1/orders").hasAnyRole(CASHIER, ADMIN)
                        .requestMatchers(GET, "/api/v1/orders/by-key/*").hasAnyRole(CASHIER, ADMIN)
                        .requestMatchers(GET, "/api/v1/orders/*/kitchen").hasAnyRole(BARISTA, ADMIN)
                        .requestMatchers(PATCH, "/api/v1/orders/*/prep-status").hasAnyRole(BARISTA, ADMIN)
                        .requestMatchers(GET, "/api/v1/orders/*").hasAnyRole(CASHIER, ADMIN)

                        .requestMatchers(GET, "/api/v1/kds/open-tickets", "/api/v1/kds/stream").hasAnyRole(BARISTA, ADMIN)

                        .requestMatchers("/api/v1/admin/**").hasRole(ADMIN)

                        .anyRequest().denyAll());
        return http.build();
    }
}
