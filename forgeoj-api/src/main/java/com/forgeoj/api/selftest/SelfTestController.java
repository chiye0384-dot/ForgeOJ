/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.selftest;

import java.util.*;
import com.forgeoj.api.auth.ForgeOjPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SelfTestController {
    private final SelfTestService service;
    public SelfTestController(SelfTestService service){this.service=service;}
    @PostMapping("/api/v1/classrooms/{id}/problems/{slug}/self-tests")
    ResponseEntity<SelfTestMapper.Run> classroom(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@PathVariable String slug,@RequestBody Map<String,Object> body){
        if(!body.keySet().equals(Set.of("requestId","language","sourceCode","input")) || !(body.get("requestId") instanceof String request) || !(body.get("language") instanceof String language) || !(body.get("sourceCode") instanceof String code) || !(body.get("input") instanceof String input)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.createClassroom(p.userId(),id,slug,request,language,code,input));
    }
    @PostMapping("/api/v1/problems/{slug}/self-tests")
    ResponseEntity<SelfTestMapper.Run> create(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String slug,@RequestBody Map<String,Object> body){
        if(!body.keySet().equals(Set.of("requestId","language","sourceCode","input")) || !(body.get("requestId") instanceof String request) || !(body.get("language") instanceof String language) || !(body.get("sourceCode") instanceof String code) || !(body.get("input") instanceof String input)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.create(p.userId(),slug,request,language,code,input));
    }
    @GetMapping("/api/v1/self-tests/{id}") ResponseEntity<SelfTestMapper.Detail> detail(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(p.userId(),id));}
    @GetMapping("/api/v1/me/self-tests") ResponseEntity<SelfTestMapper.Page> list(@AuthenticationPrincipal ForgeOjPrincipal p,@RequestParam String problemSlug,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(p.userId(),problemSlug,page,size));}
    @PostMapping("/api/v1/self-tests/{id}/cancel") ResponseEntity<SelfTestMapper.Run> cancel(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.cancel(p.userId(),id));}
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<Void> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
}
