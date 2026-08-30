package com.github.llmmock.provider.bedrock;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.github.llmmock.core.MockApiException;

/**
 * Renders failures the way Bedrock does: a {@code {"message": ...}} body plus the
 * {@code x-amzn-ErrorType} header the AWS SDKs use to pick an exception class.
 */
@RestControllerAdvice(assignableTypes = BedrockController.class)
public class BedrockExceptionHandler {

    @ExceptionHandler(MockApiException.class)
    public ResponseEntity<BedrockDtos.ErrorBody> handle(MockApiException ex) {
        return build(ex.status(), ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BedrockDtos.ErrorBody> handle(HttpMessageNotReadableException ex) {
        return build(400, "Malformed input request, please reformat your input and try again.");
    }

    private ResponseEntity<BedrockDtos.ErrorBody> build(int status, String message) {
        return ResponseEntity.status(status)
                .header("x-amzn-ErrorType", awsErrorType(status))
                .header("x-amzn-RequestId", java.util.UUID.randomUUID().toString())
                .body(new BedrockDtos.ErrorBody(message));
    }

    private String awsErrorType(int status) {
        return switch (status) {
            case 400 -> "ValidationException";
            case 403 -> "AccessDeniedException";
            case 404 -> "ResourceNotFoundException";
            case 408 -> "ModelTimeoutException";
            case 424 -> "ModelErrorException";
            case 429 -> "ThrottlingException";
            case 503 -> "ServiceUnavailableException";
            // Bedrock has no 401: an unsigned or badly signed request is a 403.
            case 401 -> "AccessDeniedException";
            default -> status >= 500 ? "InternalServerException" : "ValidationException";
        };
    }
}
