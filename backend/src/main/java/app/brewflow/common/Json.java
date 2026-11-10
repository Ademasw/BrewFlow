package app.brewflow.common;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Jackson 3 (tools.jackson) helper for jsonb columns. */
@Component
public class Json {

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }
}
