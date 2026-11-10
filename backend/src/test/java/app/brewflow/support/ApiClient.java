package app.brewflow.support;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One browser-like client: own cookie jar (session cookie) and CSRF token, real HTTP against the running server.
 */
public class ApiClient {

    public record Resp(int status, JsonNode body, String raw, HttpResponse<String> http) {
        public String code() {
            return body == null ? null : body.path("error").path("code").asString(null);
        }

        public String text(String field) {
            return body.path(field).asString(null);
        }
    }

    private final String base;
    private final ObjectMapper mapper;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http;
    private String csrfHeader;
    private String csrfToken;

    public ApiClient(int port, ObjectMapper mapper) {
        this.base = "http://127.0.0.1:" + port;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String baseUrl() {
        return base;
    }

    public Resp get(String path) {
        return send("GET", path, null, Map.of());
    }

    public Resp post(String path, Object body) {
        return send("POST", path, body, Map.of());
    }

    public Resp post(String path, Object body, Map<String, String> headers) {
        return send("POST", path, body, headers);
    }

    public Resp patch(String path, Object body) {
        return send("PATCH", path, body, Map.of());
    }

    public Resp put(String path, Object body) {
        return send("PUT", path, body, Map.of());
    }

    /** POST without the CSRF header, to prove the server refuses it. */
    public Resp postWithoutCsrf(String path, Object body) {
        return raw("POST", path, body, Map.of(), false);
    }

    public Resp login(String login, String credential) {
        ensureCsrf();
        Resp r = post("/api/v1/auth/login", Map.of("login", login, "credential", credential));
        if (r.status() == 200) {
            csrfHeader = r.body().path("csrf").path("headerName").asString();
            csrfToken = r.body().path("csrf").path("token").asString();
        }
        return r;
    }

    public void refreshCsrf() {
        Resp r = get("/api/v1/auth/csrf");
        csrfHeader = r.body().path("headerName").asString();
        csrfToken = r.body().path("token").asString();
    }

    public Optional<String> sessionCookie() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("BFSESSION"))
                .map(HttpCookie::getValue)
                .findFirst();
    }

    /** Puts a captured session cookie into this client's jar (replaying someone else's old session). */
    public void useSessionCookie(String value) {
        HttpCookie cookie = new HttpCookie("BFSESSION", value);
        cookie.setPath("/");
        cookie.setVersion(0);
        cookies.getCookieStore().add(URI.create(base), cookie);
    }

    private void ensureCsrf() {
        if (csrfToken == null) {
            refreshCsrf();
        }
    }

    private Resp send(String method, String path, Object body, Map<String, String> headers) {
        boolean mutation = !method.equals("GET");
        if (mutation) {
            ensureCsrf();
        }
        return raw(method, path, body, headers, mutation);
    }

    private Resp raw(String method, String path, Object body, Map<String, String> headers, boolean withCsrf) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
            String json = body == null ? null : (body instanceof String s ? s : mapper.writeValueAsString(body));
            b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
            if (json != null) {
                b.header("Content-Type", "application/json");
            }
            if (withCsrf && csrfToken != null) {
                b.header(csrfHeader, csrfToken);
            }
            headers.forEach(b::header);
            HttpResponse<String> response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            boolean isJson = response.headers().firstValue("Content-Type").orElse("").contains("json");
            JsonNode node = raw == null || raw.isBlank() || !isJson ? null : mapper.readTree(raw);
            return new Resp(response.statusCode(), node, raw, response);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
