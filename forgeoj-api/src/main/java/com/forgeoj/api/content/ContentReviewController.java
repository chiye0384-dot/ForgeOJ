/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import java.util.*;
import com.forgeoj.api.auth.ForgeOjPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/me/authored-problems/{draft}/reviews")
public class ContentReviewController {
    private final ContentReviewService service;
    public ContentReviewController(ContentReviewService service) {this.service=service;}
    @PostMapping ResponseEntity<ContentReviewMapper.Review> submit(@AuthenticationPrincipal ForgeOjPrincipal principal,@PathVariable String draft,@RequestBody Map<String,Object> body) {
        if(!body.keySet().equals(Set.of("expectedVersion","validationJobId","requestId")) || !(body.get("validationJobId") instanceof String job) || !(body.get("requestId") instanceof String request)) throw bad();
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.submit(principal.userId(),draft,integer(body,"expectedVersion"),job,request));
    }
    @PostMapping("/{review}/withdraw") ResponseEntity<ContentReviewMapper.Review> withdraw(@AuthenticationPrincipal ForgeOjPrincipal principal,@PathVariable String draft,@PathVariable String review,@RequestBody Map<String,Object> body) {
        if(!body.keySet().equals(Set.of("expectedVersion","expectedReviewVersion"))) throw bad();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.withdraw(principal.userId(),draft,review,integer(body,"expectedVersion"),integer(body,"expectedReviewVersion")));
    }
    @GetMapping ResponseEntity<ContentReviewMapper.Page> list(@AuthenticationPrincipal ForgeOjPrincipal principal,@PathVariable String draft,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(principal.userId(),draft,page,size));
    }
    @GetMapping("/{review}") ResponseEntity<ContentReviewMapper.Detail> detail(@AuthenticationPrincipal ForgeOjPrincipal principal,@PathVariable String draft,@PathVariable String review) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(principal.userId(),draft,review));
    }
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<Void> unavailable() {return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    private static long integer(Map<String,Object> body,String key) {var n=body.get(key);if(!(n instanceof Integer || n instanceof Long)) throw bad();return ((Number)n).longValue();}
    private static ResponseStatusException bad() {return new ResponseStatusException(HttpStatus.BAD_REQUEST);}
}
