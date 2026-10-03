/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import java.util.Map;
import java.util.Set;
import com.forgeoj.api.auth.ForgeOjPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/me/authored-problems/{draft}/validations")
public class ContentValidationController {
    private final ContentValidationService service;
    public ContentValidationController(ContentValidationService service) {this.service=service;}
    @GetMapping ResponseEntity<ContentValidationMapper.Page> list(@AuthenticationPrincipal ForgeOjPrincipal principal,@PathVariable String draft,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(principal.userId(),draft,page,size));
    }
    @PostMapping ResponseEntity<ContentValidationMapper.Result> create(@AuthenticationPrincipal ForgeOjPrincipal principal,@PathVariable String draft,@RequestBody Map<String,Object> body) {
        if(!body.keySet().equals(Set.of("expectedVersion","requestId")) || !(body.get("requestId") instanceof String request)
                || !(body.get("expectedVersion") instanceof Integer || body.get("expectedVersion") instanceof Long)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.create(principal.userId(),draft,((Number)body.get("expectedVersion")).longValue(),request));
    }
    @GetMapping("/{job}") ResponseEntity<ContentValidationMapper.Result> result(@AuthenticationPrincipal ForgeOjPrincipal principal,@PathVariable String draft,@PathVariable String job) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.result(principal.userId(),draft,job));
    }
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<Void> unavailable() {return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
}
