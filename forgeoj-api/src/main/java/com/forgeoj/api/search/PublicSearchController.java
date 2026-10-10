/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.search;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;
@RestController
public class PublicSearchController {
    private final PublicSearchService service;
    public PublicSearchController(PublicSearchService service){this.service=service;}
    @GetMapping("/api/v1/problems/search") ResponseEntity<?> search(@RequestParam String keyword,@RequestParam(required=false)String difficulty,
        @RequestParam(required=false)String tag,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.search(keyword,difficulty,tag,page,size));
    }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<Void> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<Void> rejected(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();}
}
