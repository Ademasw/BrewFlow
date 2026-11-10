package app.brewflow.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException ex) {
        return respond(ex.code(), ex.getMessage(), ex.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException ex) {
        // field names and messages only; rejected values may be credentials and are never echoed
        List<Map<String, String>> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::field)
                .toList();
        return respond(ErrorCode.INVALID_INPUT, "The request is invalid.", Map.of("fields", fields));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Map<String, Object>> invalidParameters(HandlerMethodValidationException ex) {
        return respond(ErrorCode.INVALID_INPUT, "The request is invalid.", Map.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<Map<String, Object>> unreadable(Exception ex) {
        return respond(ErrorCode.INVALID_INPUT, "The request body or parameters could not be read.", Map.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, Object>> missingHeader(MissingRequestHeaderException ex) {
        return respond(ErrorCode.INVALID_INPUT, "Header " + ex.getHeaderName() + " is required.",
                Map.of("header", ex.getHeaderName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException ex) {
        if (ex.getParameter().hasParameterAnnotation(org.springframework.web.bind.annotation.PathVariable.class)) {
            return respond(ErrorCode.NOT_FOUND, "Not found.", Map.of());
        }
        return respond(ErrorCode.INVALID_INPUT, "Parameter " + ex.getName() + " is invalid.", Map.of("field", ex.getName()));
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<Map<String, Object>> notFound(Exception ex) {
        return respond(ErrorCode.NOT_FOUND, "Not found.", Map.of());
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, DataAccessResourceFailureException.class,
            TransientDataAccessException.class})
    ResponseEntity<Map<String, Object>> unavailable(Exception ex) {
        log.error("Database unavailable: {}", ex.getClass().getSimpleName());
        return respond(ErrorCode.TEMPORARILY_UNAVAILABLE,
                "The service is temporarily unavailable. Use the cafe's KKM and a paper ticket.", Map.of());
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void clientGone() {
        // an SSE client disconnected; nothing to send
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "Unexpected error. Do not repeat the fiscal action.", Map.of());
    }

    private static Map<String, String> field(FieldError e) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("field", e.getField());
        m.put("message", e.getDefaultMessage());
        return m;
    }

    private static ResponseEntity<Map<String, Object>> respond(ErrorCode code, String message, Map<String, ?> details) {
        return ResponseEntity.status(code.status()).body(ApiErrorWriter.envelope(code, message, details));
    }
}
