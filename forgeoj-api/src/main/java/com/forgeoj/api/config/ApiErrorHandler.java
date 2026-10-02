package com.forgeoj.api.config;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackages = "com.forgeoj.api")
public final class ApiErrorHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Void> rejected(ResponseStatusException failure) {
        // sendError starts a second /error dispatch without the request's JWT identity.
        // Preserve the business status without exposing exception reasons or details.
        return ResponseEntity.status(failure.getStatusCode()).build();
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Void> malformedRequest() {
        return ResponseEntity.badRequest().build();
    }
}
