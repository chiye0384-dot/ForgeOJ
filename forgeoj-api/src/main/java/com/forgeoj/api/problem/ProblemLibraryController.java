/*
 * Copyright 2026 池也
 * SPDX-License-Identifier: Apache-2.0
 */
package com.forgeoj.api.problem;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProblemLibraryController {

    private final ProblemLibraryService service;

    public ProblemLibraryController(ProblemLibraryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/problems")
    ProblemLibraryService.ProblemPage list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(keyword, difficulty, tag, page, size);
    }

    @GetMapping("/api/v1/problem-tags")
    ProblemLibraryService.ProblemTags tags() {
        return service.tags();
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
