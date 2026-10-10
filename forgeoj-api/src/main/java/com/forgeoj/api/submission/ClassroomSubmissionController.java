/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.submission;

import java.util.*;
import com.forgeoj.api.auth.ForgeOjPrincipal;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ClassroomSubmissionController {
    private final ClassroomSubmissionService service;
    private final com.forgeoj.api.cache.RequestCoalescer coalescer;
    public ClassroomSubmissionController(ClassroomSubmissionService service,com.forgeoj.api.cache.RequestCoalescer coalescer) {this.service=service;this.coalescer=coalescer;}
    @PostMapping("/api/v1/classrooms/{id}/problems/{slug}/submissions")
    ResponseEntity<?> create(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@PathVariable String slug,@RequestBody Map<String,Object> b) {
        if(!b.keySet().equals(Set.of("clientRequestId","language","sourceCode"))||!(b.get("clientRequestId") instanceof String request)||!(b.get("language") instanceof String language)||!(b.get("sourceCode") instanceof String code)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(coalescer.execute(p.userId(),request,()->service.create(p.userId(),id,slug,request,language,code)));
    }
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> rejected(ResponseStatusException e) {return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,IllegalStateException.class}) ResponseEntity<?> unavailable() {return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
}
