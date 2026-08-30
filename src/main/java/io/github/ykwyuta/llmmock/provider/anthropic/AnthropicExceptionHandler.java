package io.github.ykwyuta.llmmock.provider.anthropic;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.ykwyuta.llmmock.core.MockApiException;

/** Renders failures in Anthropic's error envelope. */
@RestControllerAdvice(assignableTypes = AnthropicController.class)
public class AnthropicExceptionHandler {

    @ExceptionHandler(MockApiException.class)
    public ResponseEntity<AnthropicDtos.ErrorEnvelope> handle(MockApiException ex) {
        return ResponseEntity.status(ex.status()).body(envelope(ex.status(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AnthropicDtos.ErrorEnvelope> handle(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(envelope(400, "Malformed JSON body"));
    }

    private AnthropicDtos.ErrorEnvelope envelope(int status, String message) {
        return new AnthropicDtos.ErrorEnvelope("error",
                new AnthropicDtos.ErrorBody(anthropicType(status), message),
                AnthropicWire.requestId());
    }

    private String anthropicType(int status) {
        return switch (status) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 413 -> "request_too_large";
            case 429 -> "rate_limit_error";
            case 529 -> "overloaded_error";
            default -> status >= 500 ? "api_error" : "invalid_request_error";
        };
    }
}
