package io.github.ykwyuta.llmmock.provider.gemini;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.ykwyuta.llmmock.core.MockApiException;

/** Renders failures in the standard Google API error envelope. */
@RestControllerAdvice(assignableTypes = GeminiController.class)
public class GeminiExceptionHandler {

    @ExceptionHandler(MockApiException.class)
    public ResponseEntity<GeminiDtos.ErrorEnvelope> handle(MockApiException ex) {
        return ResponseEntity.status(ex.status()).body(envelope(ex.status(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<GeminiDtos.ErrorEnvelope> handle(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(envelope(400, "Invalid JSON payload"));
    }

    private GeminiDtos.ErrorEnvelope envelope(int status, String message) {
        return new GeminiDtos.ErrorEnvelope(
                new GeminiDtos.ErrorBody(status, message, googleStatus(status), List.of()));
    }

    private String googleStatus(int status) {
        return switch (status) {
            case 400 -> "INVALID_ARGUMENT";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "PERMISSION_DENIED";
            case 404 -> "NOT_FOUND";
            case 409 -> "ABORTED";
            case 429 -> "RESOURCE_EXHAUSTED";
            case 503 -> "UNAVAILABLE";
            case 504 -> "DEADLINE_EXCEEDED";
            default -> status >= 500 ? "INTERNAL" : "INVALID_ARGUMENT";
        };
    }
}
