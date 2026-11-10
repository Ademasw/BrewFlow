package app.brewflow.common;

import java.util.Map;

public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, ?> details) {
        super(message);
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Not found.");
    }

    public static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.INVALID_INPUT, message, Map.of("field", field));
    }
}
