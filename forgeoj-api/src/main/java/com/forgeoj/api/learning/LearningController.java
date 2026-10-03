/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.learning;

import static com.forgeoj.api.learning.LearningRecords.*;
import java.util.*;
import com.forgeoj.api.auth.ForgeOjPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class LearningController {
    private final LearningService service;
    public LearningController(LearningService service) { this.service=service; }
    @GetMapping("/api/v1/me/problem-lists")
    ResponseEntity<Page<ListSummary>> lists(@AuthenticationPrincipal ForgeOjPrincipal p,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return privateResult(service.lists(p.userId(),false,page,size)); }
    @GetMapping("/api/v1/me/problem-lists/{id}")
    ResponseEntity<ListDetail> detail(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return privateResult(service.detail(p.userId(),id,false,page,size)); }
    @PostMapping("/api/v1/me/problem-lists")
    ResponseEntity<ListSummary> create(@AuthenticationPrincipal ForgeOjPrincipal p,@RequestBody Map<String,Object> body) { keys(body,"title"); return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.create(p.userId(),new TitleBody(string(body,"title"),null))); }
    @PatchMapping("/api/v1/me/problem-lists/{id}")
    ResponseEntity<ListSummary> rename(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestBody Map<String,Object> body) { keys(body,"title","expectedVersion"); return privateResult(service.rename(p.userId(),id,new TitleBody(string(body,"title"),version(body)))); }
    @DeleteMapping("/api/v1/me/problem-lists/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestParam Long expectedVersion) { service.delete(p.userId(),id,expectedVersion); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build(); }
    @PostMapping("/api/v1/me/problem-lists/{id}/items")
    ResponseEntity<ListSummary> add(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestBody Map<String,Object> body) { keys(body,"problemSlug","expectedVersion"); return privateResult(service.add(p.userId(),id,new AddBody(string(body,"problemSlug"),version(body)))); }
    @DeleteMapping("/api/v1/me/problem-lists/{id}/items/{itemId}")
    ResponseEntity<ListSummary> remove(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@PathVariable String itemId,@RequestParam Long expectedVersion) { return privateResult(service.remove(p.userId(),id,itemId,expectedVersion)); }
    @PutMapping("/api/v1/me/problem-lists/{id}/order")
    ResponseEntity<ListSummary> order(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestBody Map<String,Object> body) {
        keys(body,"itemIds","expectedVersion");
        if(!(body.get("itemIds") instanceof List<?> ids) || ids.stream().anyMatch(i -> !(i instanceof String))) throw bad();
        return privateResult(service.order(p.userId(),id,new OrderBody(ids.stream().map(String.class::cast).toList(),version(body))));
    }
    @GetMapping("/api/v1/official-problem-lists")
    Page<ListSummary> officialLists(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return service.lists(0,true,page,size); }
    @GetMapping("/api/v1/official-problem-lists/{id}")
    ListDetail official(@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return service.detail(0,id,true,page,size); }
    @GetMapping("/api/v1/me/official-problem-lists/{id}")
    ResponseEntity<ListDetail> officialProgress(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return privateResult(service.detail(p.userId(),id,true,page,size)); }
    @GetMapping("/api/v1/me/submissions")
    ResponseEntity<Page<History>> history(@AuthenticationPrincipal ForgeOjPrincipal p,@RequestParam(required=false) String problemSlug,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return privateResult(service.history(p.userId(),problemSlug,page,size)); }
    @GetMapping("/api/v1/me/problems/{slug}/draft")
    ResponseEntity<Draft> draft(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String slug,@RequestParam(defaultValue="JAVA_21") String language) { return privateResult(service.draft(p.userId(),slug,language)); }
    @PutMapping("/api/v1/me/problems/{slug}/draft")
    ResponseEntity<Draft> save(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String slug,@RequestBody Map<String,Object> body) { keys(body,"language","sourceCode","expectedVersion"); return privateResult(service.saveDraft(p.userId(),slug,new DraftBody(string(body,"language"),string(body,"sourceCode"),version(body)))); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build(); }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> rejected(ResponseStatusException e) {
        if(e.getStatusCode().value()==409) return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(Map.of("code","VERSION_OR_RESOURCE_CONFLICT"));
        return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();
    }
    private static <T> ResponseEntity<T> privateResult(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    private static void keys(Map<String,Object> body,String... keys) { if(!body.keySet().equals(Set.of(keys))) throw bad(); }
    private static String string(Map<String,Object> body,String key) { if(!(body.get(key) instanceof String value)) throw bad(); return value; }
    private static Long version(Map<String,Object> body) { Object v=body.get("expectedVersion"); if(!(v instanceof Integer || v instanceof Long)) throw bad(); return ((Number)v).longValue(); }
    private static ResponseStatusException bad() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
