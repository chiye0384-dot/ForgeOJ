/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.solution;

import com.forgeoj.api.auth.ForgeOjPrincipal;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/me/problems/{slug}/solution")
public class SolutionController {
  private final SolutionService service;

  public SolutionController(SolutionService service) {
    this.service = service;
  }

  @GetMapping
  ResponseEntity<SolutionService.Access> read(
      @AuthenticationPrincipal ForgeOjPrincipal p, @PathVariable String slug) {
    return result(service.read(p.userId(), slug));
  }

  @PostMapping("/early-view")
  ResponseEntity<SolutionService.Access> confirm(
      @AuthenticationPrincipal ForgeOjPrincipal p,
      @PathVariable String slug,
      @RequestBody Map<String, Object> body) {
    Object version = body.get("judgeVersion");
    if (!body.keySet().equals(Set.of("judgeVersion", "confirmEarlyView"))
        || !Boolean.TRUE.equals(body.get("confirmEarlyView"))
        || !(version instanceof Integer || version instanceof Long)
        || ((Number) version).longValue() < 1
        || ((Number) version).longValue() > Integer.MAX_VALUE)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return result(service.confirm(p.userId(), slug, ((Number) version).intValue()));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<Void> unavailable() {
    return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Void> rejected(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();
  }

  private static <T> ResponseEntity<T> result(T value) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
  }
}
