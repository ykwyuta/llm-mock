package com.example.llmmock.admin;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.llmmock.core.MockApiException;

@RestControllerAdvice(assignableTypes = AdminController.class)
public class AdminExceptionHandler {

    @ExceptionHandler(MockApiException.class)
    public ResponseEntity<Map<String, Object>> handle(MockApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(Map.of("error", Map.of("type", ex.type(), "message", ex.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handle(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid request");
        return ResponseEntity.badRequest()
                .body(Map.of("error", Map.of("type", "invalid_request", "message", message)));
    }
}
