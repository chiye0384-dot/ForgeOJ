/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.learning;

import com.forgeoj.api.auth.ForgeOjPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PracticeController {
  private final PracticeService service;

  public PracticeController(PracticeService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/me/practice-problems")
  ResponseEntity<?> list(
      @AuthenticationPrincipal ForgeOjPrincipal user,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String difficulty,
      @RequestParam(required = false) String tag,
      @RequestParam(required = false) String progress,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.list(user.userId(), keyword, difficulty, tag, progress, page, size));
  }

  @GetMapping("/api/v1/me/submissions/{id}/source")
  ResponseEntity<?> source(
      @AuthenticationPrincipal ForgeOjPrincipal user, @PathVariable String id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.source(user.userId(), id));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<Void> unavailable() {
    return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Void> rejected(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();
  }
}
