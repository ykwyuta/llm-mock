package com.example.llmmock.provider.openai;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.llmmock.core.MockApiException;

/** Renders failures in OpenAI's error envelope. */
@RestControllerAdvice(assignableTypes = OpenAiController.class)
public class OpenAiExceptionHandler {

    @ExceptionHandler(MockApiException.class)
    public ResponseEntity<OpenAiDtos.ErrorEnvelope> handle(MockApiException ex) {
        return ResponseEntity.status(ex.status()).body(envelope(ex.status(), ex.type(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<OpenAiDtos.ErrorEnvelope> handle(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(envelope(400, "invalid_request", "Malformed JSON body"));
    }

    private OpenAiDtos.ErrorEnvelope envelope(int status, String type, String message) {
        return new OpenAiDtos.ErrorEnvelope(
                new OpenAiDtos.ErrorBody(message, openAiType(status, type), null, code(status)));
    }

    private String openAiType(int status, String type) {
        return switch (status) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 429 -> "rate_limit_error";
            default -> status >= 500 ? "server_error" : (type == null ? "invalid_request_error" : type);
        };
    }

    private String code(int status) {
        return switch (status) {
            case 401 -> "invalid_api_key";
            case 404 -> "model_not_found";
            case 429 -> "rate_limit_exceeded";
            default -> null;
        };
    }
}
