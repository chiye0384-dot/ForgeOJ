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
@RequestMapping("/api/v1/classrooms/{room}/assignments")
public class AssignmentController {
    private final AssignmentService service;
    public AssignmentController(AssignmentService service) {this.service=service;}
    @GetMapping ResponseEntity<?> list(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String room,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {return result(service.list(p.userId(),room,page,size));}
    @GetMapping("/{id}") ResponseEntity<?> detail(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String room,@PathVariable String id) {return result(service.detail(p.userId(),room,id));}
    @PostMapping ResponseEntity<?> create(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String room,@RequestBody Map<String,Object> b) {keys(b,"clientRequestId","definition");return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.create(p.userId(),room,text(b,"clientRequestId"),definition(b.get("definition"))));}
    @PutMapping("/{id}") ResponseEntity<?> edit(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String room,@PathVariable String id,@RequestBody Map<String,Object> b) {keys(b,"expectedVersion","definition");return result(service.edit(p.userId(),room,id,number(b,"expectedVersion"),definition(b.get("definition"))));}
    @PostMapping("/{id}/publish") ResponseEntity<?> publish(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String room,@PathVariable String id,@RequestBody Map<String,Object> b) {keys(b,"expectedVersion","startsAt");if(b.get("startsAt")!=null&&!(b.get("startsAt") instanceof String)) throw ClassroomService.bad();return result(service.publish(p.userId(),room,id,number(b,"expectedVersion"),(String)b.get("startsAt")));}
    @PostMapping("/{id}/cancel") ResponseEntity<?> cancel(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String room,@PathVariable String id,@RequestBody Map<String,Object> b) {keys(b,"expectedVersion","reason");return result(service.cancel(p.userId(),room,id,number(b,"expectedVersion"),text(b,"reason")));}
    @PostMapping("/{id}/participants") ResponseEntity<?> add(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String room,@PathVariable String id,@RequestBody Map<String,Object> b) {keys(b,"expectedVersion","userId");return result(service.add(p.userId(),room,id,number(b,"expectedVersion"),number(b,"userId")));}
    @PostMapping("/{id}/copy") ResponseEntity<?> copy(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String room,@PathVariable String id,@RequestBody Map<String,Object> b) {keys(b,"clientRequestId","deadlineAt");return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.copy(p.userId(),room,id,text(b,"clientRequestId"),text(b,"deadlineAt")));}
    @GetMapping("/{id}/problems/{slug}/solution") ResponseEntity<?> solution(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String room,@PathVariable String id,@PathVariable String slug) {return result(service.solution(p.userId(),room,id,slug));}
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<?> unavailable() {return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> rejected(ResponseStatusException e) {return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).body(e.getStatusCode().value()==409?Map.of("code","VERSION_OR_ASSIGNMENT_CONFLICT"):null);}
    private static ResponseEntity<?> result(Object value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    private static void keys(Map<String,Object> b,String... keys) {if(!b.keySet().equals(Set.of(keys))) throw ClassroomService.bad();}
    private static String text(Map<String,Object> b,String key) {if(!(b.get(key) instanceof String s)) throw ClassroomService.bad();return s;}
    private static long number(Map<String,Object> b,String key) {if(!(b.get(key) instanceof Integer||b.get(key) instanceof Long)) throw ClassroomService.bad();return ((Number)b.get(key)).longValue();}
    private static boolean bool(Map<String,Object> b,String key) {if(!(b.get(key) instanceof Boolean v)) throw ClassroomService.bad();return v;}
    private static AssignmentService.Definition definition(Object input) {
        if(!(input instanceof Map<?,?> raw)||raw.keySet().stream().anyMatch(k->!(k instanceof String))) throw ClassroomService.bad();
        var b=new HashMap<String,Object>();raw.forEach((k,v)->b.put((String)k,v));keys(b,"title","description","deadlineAt","acceptExistingAc","allowLate","solutionPolicy","problemSlugs");
        if(!(b.get("problemSlugs") instanceof List<?> list)||list.stream().anyMatch(s->!(s instanceof String))) throw ClassroomService.bad();
        return new AssignmentService.Definition(text(b,"title"),text(b,"description"),text(b,"deadlineAt"),bool(b,"acceptExistingAc"),bool(b,"allowLate"),text(b,"solutionPolicy"),list.stream().map(s->(String)s).toList());
    }
}
