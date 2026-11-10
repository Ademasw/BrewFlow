package app.brewflow.common;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Builds the section 7 error envelope, for controllers and for servlet filters. */
@Component
public class ApiErrorWriter {

    private final ObjectMapper mapper;

    public ApiErrorWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static Map<String, Object> envelope(ErrorCode code, String message, Map<String, ?> details) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.name());
        error.put("message", message);
        error.put("requestId", MDC.get(RequestIdFilter.MDC_REQUEST_ID));
        error.put("details", details == null ? Map.of() : details);
        return Map.of("error", error);
    }

    public void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(envelope(code, message, Map.of())));
    }
}
