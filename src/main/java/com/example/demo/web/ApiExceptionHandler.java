package com.example.demo.web;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of(
                "status", "ERROR",
                "error", exception.getMessage(),
                "message", exception.getMessage()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpectedException(Exception exception) {
        String msg = exception.getMessage() != null && !exception.getMessage().isBlank() ? exception.getMessage() : "The request could not be completed.";
        return ResponseEntity.internalServerError().body(Map.of(
                "status", "ERROR",
                "error", msg,
                "message", msg
        ));
    }
}
