package app.brewflow;

import app.brewflow.support.ApiClient.Resp;
import app.brewflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** Swagger UI and the OpenAPI document are served and describe the section 7 contract. */
class OpenApiDocsTest extends IntegrationTest {

    @Test
    void documentListsTheContract() {
        Resp r = client().get("/v3/api-docs");
        assertThat(r.status()).isEqualTo(200);
        JsonNode doc = r.body();
        assertThat(doc.path("info").path("version").asString()).isEqualTo("0.3.2-draft");
        JsonNode paths = doc.path("paths");
        assertThat(paths.has("/api/v1/orders")).isTrue();
        assertThat(paths.has("/api/v1/orders/quotes")).isTrue();
        assertThat(paths.has("/api/v1/admin/quotes/{quoteId}/recover")).isTrue();
        assertThat(paths.has("/api/v1/kds/stream")).isTrue();
        assertThat(paths.has("/actuator/health")).isFalse();
        assertThat(paths.path("/api/v1/orders").path("post").path("parameters").toString()).contains("Idempotency-Key");
        assertThat(paths.path("/api/v1/orders").path("post").path("responses").has("4XX")).isTrue();
        assertThat(doc.path("components").path("securitySchemes").has("csrf")).isTrue();
        assertThat(doc.path("components").path("schemas").has("ErrorEnvelope")).isTrue();
    }

    @Test
    void swaggerUiIsServed() {
        assertThat(client().get("/swagger-ui/index.html").status()).isEqualTo(200);
        assertThat(client().get("/v3/api-docs.yaml").status()).isEqualTo(200);
    }

    @Test
    void docsDoNotOpenTheApi() {
        assertThat(client().get("/api/v1/admin/staff").status()).isEqualTo(401);
    }
}
