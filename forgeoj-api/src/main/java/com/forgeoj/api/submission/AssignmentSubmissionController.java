/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.submission;

import java.util.*;
import com.forgeoj.api.auth.ForgeOjPrincipal;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/classrooms/{room}/assignments/{assignment}/problems/{slug}/submissions")
public class AssignmentSubmissionController {
    private final AssignmentSubmissionService service;
    private final com.forgeoj.api.cache.RequestCoalescer coalescer;
    public AssignmentSubmissionController(AssignmentSubmissionService service,com.forgeoj.api.cache.RequestCoalescer coalescer) {this.service=service;this.coalescer=coalescer;}
    @PostMapping ResponseEntity<?> create(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String room,@PathVariable String assignment,@PathVariable String slug,@RequestBody Map<String,Object> b) {
        if(!b.keySet().equals(Set.of("clientRequestId","language","sourceCode"))||b.values().stream().anyMatch(v->!(v instanceof String))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(coalescer.execute(p.userId(),(String)b.get("clientRequestId"),()->service.create(p.userId(),room,assignment,slug,(String)b.get("clientRequestId"),(String)b.get("language"),(String)b.get("sourceCode"))));
    }
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<?> unavailable() {return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> rejected(ResponseStatusException e) {return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).body(e.getStatusCode().value()==409?Map.of("code","ASSIGNMENT_OR_REQUEST_CONFLICT"):null);}
}
