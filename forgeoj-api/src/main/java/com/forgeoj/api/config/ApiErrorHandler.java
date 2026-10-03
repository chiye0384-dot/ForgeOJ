package com.forgeoj.api.config;

import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public final class ApiErrorHandler {
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Void> unsupportedMethod(HttpRequestMethodNotSupportedException failure) {
        var response=ResponseEntity.status(405).cacheControl(CacheControl.noStore());
        var allowed=failure.getSupportedMethods();
        if(allowed!=null) response.allow(java.util.Arrays.stream(allowed).map(HttpMethod::valueOf).toArray(HttpMethod[]::new));
        return response.build();
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Void> rejected(ResponseStatusException failure) {
        // sendError starts a second /error dispatch without the request's JWT identity.
        // Preserve the business status without exposing exception reasons or details.
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Void> malformedRequest() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
    }
}
