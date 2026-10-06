/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.classroom;

import java.util.*;
import com.forgeoj.api.auth.ForgeOjPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/classrooms/{id}/problems")
public class ClassroomProblemController {
    private final ClassroomProblemService service;
    public ClassroomProblemController(ClassroomProblemService service) {this.service=service;}
    @GetMapping ResponseEntity<?> list(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {return result(service.list(p.userId(),id,page,size));}
    @GetMapping("/{slug}") ResponseEntity<?> detail(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@PathVariable String slug) {return result(service.detail(p.userId(),id,slug));}
    @GetMapping("/{slug}/maintenance") ResponseEntity<?> maintenance(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@PathVariable String slug) {return result(service.maintenance(p.userId(),id,slug));}
    @GetMapping("/{slug}/solution") ResponseEntity<?> solution(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@PathVariable String slug) {return result(service.solution(p.userId(),id,slug));}
    @PostMapping("/{slug}/solution/early-view") ResponseEntity<?> earlyView(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@PathVariable String slug,@RequestBody Map<String,Object> b) {
        keys(b,"expectedVersion","confirmEarlyView");if(!Boolean.TRUE.equals(b.get("confirmEarlyView"))) throw ClassroomService.bad();
        return result(service.confirmSolution(p.userId(),id,slug,number(b,"expectedVersion")));
    }
    @PostMapping ResponseEntity<?> publish(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestBody Map<String,Object> b) {
        keys(b,"draftId","draftVersion","validationJobId","clientRequestId","solutionPolicy");
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.publish(p.userId(),id,text(b,"draftId"),number(b,"draftVersion"),text(b,"validationJobId"),text(b,"clientRequestId"),text(b,"solutionPolicy")));
    }
    @PostMapping("/{slug}/archive") ResponseEntity<?> archive(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@PathVariable String slug,@RequestBody Map<String,Object> b) {keys(b,"expectedVersion");service.archive(p.userId(),id,slug,number(b,"expectedVersion"));return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
    @PostMapping("/{slug}/copy") ResponseEntity<?> copy(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@PathVariable String slug,@RequestBody Map<String,Object> b) {keys(b);return result(service.copy(p.userId(),id,slug));}
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<?> unavailable() {return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> rejected(ResponseStatusException e) {return e.getStatusCode().value()==409?ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(Map.of("code","VERSION_OR_RESOURCE_CONFLICT")):ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();}
    private static ResponseEntity<?> result(Object value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    private static void keys(Map<String,Object> b,String... keys) {if(!b.keySet().equals(Set.of(keys))) throw ClassroomService.bad();}
    private static String text(Map<String,Object> b,String key) {if(!(b.get(key) instanceof String s)) throw ClassroomService.bad();return s;}
    private static long number(Map<String,Object> b,String key) {if(!(b.get(key) instanceof Integer||b.get(key) instanceof Long)) throw ClassroomService.bad();return ((Number)b.get(key)).longValue();}
}
