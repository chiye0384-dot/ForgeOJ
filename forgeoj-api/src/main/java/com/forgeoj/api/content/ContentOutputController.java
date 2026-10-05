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
@RequestMapping("/api/v1/me/authored-problems/{draft}/output-previews")
public class ContentOutputController {
    private final ContentOutputService service;
    public ContentOutputController(ContentOutputService service){this.service=service;}
    @GetMapping ResponseEntity<ContentOutputMapper.Page> list(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String draft,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ok(service.list(p.userId(),draft,page,size));}
    @PostMapping ResponseEntity<ContentOutputMapper.Preview> create(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String draft,@RequestBody Map<String,Object> b){
        keys(b,"expectedVersion","requestId");if(!(b.get("requestId") instanceof String request)) throw bad();
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.create(p.userId(),draft,version(b),request));
    }
    @GetMapping("/{job}") ResponseEntity<ContentOutputMapper.Detail> detail(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String draft,@PathVariable String job){return ok(service.detail(p.userId(),draft,job));}
    @PostMapping("/{job}/accept") ResponseEntity<ContentOutputMapper.Receipt> accept(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String draft,@PathVariable String job,@RequestBody Map<String,Object> b){keys(b,"expectedVersion");return ok(service.accept(p.userId(),draft,job,version(b)));}
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<Void> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    private static long version(Map<String,Object> b){if(!(b.get("expectedVersion") instanceof Integer || b.get("expectedVersion") instanceof Long)) throw bad();return ((Number)b.get("expectedVersion")).longValue();}
    private static void keys(Map<String,Object> b,String... fields){if(!b.keySet().equals(Set.of(fields))) throw bad();}
    private static <T> ResponseEntity<T> ok(T value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST);}
}
