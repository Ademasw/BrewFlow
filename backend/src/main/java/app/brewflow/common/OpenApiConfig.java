package app.brewflow.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui.html, the document at /v3/api-docs (section 7 contract, version 0.3.2-draft). */
@Configuration
public class OpenApiConfig {

    public static final String SESSION = "session";
    public static final String CSRF = "csrf";
    private static final String ERROR = "ErrorEnvelope";

    private static final String DESCRIPTION = """
            BrewFlow pilot API (CANDIDATE, not owner-approved). Same-origin JSON under `/api/v1`, server-side session
            cookie `BFSESSION`, CSRF header on every mutation.

            **Try it out in this page**
            1. `GET /api/v1/auth/csrf` → copy `token`, press **Authorize** and paste it into `csrf` (header `X-CSRF-TOKEN`).
            2. `POST /api/v1/auth/login` → the session rotates and the response carries a new `csrf.token`:
               paste it into **Authorize → csrf** again. The browser keeps the session cookie by itself.
            3. While `mustChangeCredential` is true, every route except csrf, me, credential and logout returns
               `403 CREDENTIAL_CHANGE_REQUIRED`.

            `POST /api/v1/orders` needs header `Idempotency-Key` (UUID). Money is a string with two decimals.
            Errors use one envelope: `{"error":{"code","message","requestId","details"}}`.
            """;

    @Bean
    OpenAPI brewFlowOpenApi() {
        return new OpenAPI()
                .info(new Info().title("BrewFlow API").version("0.3.2-draft").description(DESCRIPTION))
                .components(new Components()
                        .addSecuritySchemes(SESSION, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE).name("BFSESSION")
                                .description("Set by POST /auth/login; the browser sends it automatically."))
                        .addSecuritySchemes(CSRF, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-CSRF-TOKEN")
                                .description("Token from GET /auth/csrf, and again from the login response.")))
                .security(List.of(new SecurityRequirement().addList(SESSION).addList(CSRF)));
    }

    /**
     * Every operation documents the shared error envelope for 4xx and 5xx answers. The schema is added here,
     * after springdoc has built the components, so it is not pruned as unreferenced.
     */
    @Bean
    OpenApiCustomizer errorEnvelopeResponses() {
        return openApi -> {
            openApi.getComponents().addSchemas(ERROR, errorSchema());
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(op -> {
            Content content = new Content().addMediaType("application/json",
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + ERROR)));
            op.getResponses().putIfAbsent("4XX", new ApiResponse()
                    .description("Error envelope (section 7): 401, 403, 404, 409, 422, 429").content(content));
            op.getResponses().putIfAbsent("5XX", new ApiResponse()
                    .description("Error envelope: 500 INTERNAL_ERROR, 503 TEMPORARILY_UNAVAILABLE").content(content));
            }));
        };
    }

    private static Schema<?> errorSchema() {
        return new ObjectSchema()
                .addProperty("error", new ObjectSchema()
                        .addProperty("code", new StringSchema().example("QUOTE_EXPIRED"))
                        .addProperty("message", new StringSchema()
                                .example("The quote expired. Do not repeat the fiscal action. Ask a manager to recover this quote."))
                        .addProperty("requestId", new StringSchema())
                        .addProperty("details", new ObjectSchema()));
    }
}
