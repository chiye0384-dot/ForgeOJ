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
public class ClassroomController {
    private final ClassroomService service;
    public ClassroomController(ClassroomService service) {this.service=service;}
    @GetMapping("/api/v1/me/classrooms")
    ResponseEntity<?> list(@AuthenticationPrincipal ForgeOjPrincipal p,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {return result(service.list(p.userId(),page,size));}
    @PostMapping("/api/v1/classrooms")
    ResponseEntity<?> create(@AuthenticationPrincipal ForgeOjPrincipal p,@RequestBody Map<String,Object> b) {keys(b,"title","clientRequestId");return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.create(p.userId(),text(b,"title"),text(b,"clientRequestId")));}
    @PostMapping("/api/v1/classrooms/join")
    ResponseEntity<?> join(@AuthenticationPrincipal ForgeOjPrincipal p,@RequestBody Map<String,Object> b) {keys(b,"inviteCode");return result(service.join(p.userId(),text(b,"inviteCode")));}
    @GetMapping("/api/v1/classrooms/{id}")
    ResponseEntity<?> detail(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id) {return result(service.get(p.userId(),id));}
    @PatchMapping("/api/v1/classrooms/{id}")
    ResponseEntity<?> rename(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestBody Map<String,Object> b) {keys(b,"title","expectedVersion");return result(service.rename(p.userId(),id,text(b,"title"),number(b,"expectedVersion")));}
    @PostMapping("/api/v1/classrooms/{id}/invite")
    ResponseEntity<?> invite(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestBody Map<String,Object> b) {keys(b,"enabled","expectedVersion");if(!(b.get("enabled") instanceof Boolean enabled)) throw ClassroomService.bad();return result(service.invite(p.userId(),id,enabled,number(b,"expectedVersion")));}
    @PostMapping("/api/v1/classrooms/{id}/members/{userId}/{action}")
    ResponseEntity<?> member(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@PathVariable long userId,@PathVariable String action,@RequestBody Map<String,Object> b) {keys(b,action.equals("role")?new String[]{"role","expectedVersion"}:new String[]{"expectedVersion"});return result(service.memberAction(p.userId(),id,userId,action,action.equals("role")?text(b,"role"):null,number(b,"expectedVersion")));}
    @PostMapping("/api/v1/classrooms/{id}/leave")
    ResponseEntity<?> leave(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestBody Map<String,Object> b) {keys(b,"expectedVersion");service.leave(p.userId(),id,number(b,"expectedVersion"));return empty();}
    @PostMapping("/api/v1/classrooms/{id}/transfers")
    ResponseEntity<?> transfer(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestBody Map<String,Object> b) {keys(b,"targetUserId","clientRequestId","expectedVersion");return result(service.propose(p.userId(),id,number(b,"targetUserId"),text(b,"clientRequestId"),number(b,"expectedVersion")));}
    @PostMapping("/api/v1/classrooms/{id}/transfers/{transferId}/{action}")
    ResponseEntity<?> transferAction(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@PathVariable String transferId,@PathVariable String action,@RequestBody Map<String,Object> b) {keys(b,"expectedVersion");return result(service.transferAction(p.userId(),id,transferId,action,number(b,"expectedVersion")));}
    @PostMapping({"/api/v1/classrooms/{id}/archive","/api/v1/classrooms/{id}/restore"})
    ResponseEntity<?> lifecycle(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestBody Map<String,Object> b,jakarta.servlet.http.HttpServletRequest request) {keys(b,"expectedVersion");service.lifecycle(p.userId(),id,request.getRequestURI().endsWith("/archive")?"archive":"restore",number(b,"expectedVersion"));return empty();}
    @DeleteMapping("/api/v1/classrooms/{id}")
    ResponseEntity<?> delete(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestParam long expectedVersion) {service.delete(p.userId(),id,expectedVersion);return empty();}
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable() {return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> rejected(ResponseStatusException e) {return e.getStatusCode().value()==409?ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(Map.of("code","VERSION_OR_RESOURCE_CONFLICT")):ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();}
    private static ResponseEntity<?> result(Object value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    private static ResponseEntity<?> empty() {return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
    private static void keys(Map<String,Object> b,String... keys) {if(!b.keySet().equals(Set.of(keys))) throw ClassroomService.bad();}
    private static String text(Map<String,Object> b,String key) {if(!(b.get(key) instanceof String s)) throw ClassroomService.bad();return s;}
    private static long number(Map<String,Object> b,String key) {if(!(b.get(key) instanceof Integer||b.get(key) instanceof Long)) throw ClassroomService.bad();return ((Number)b.get(key)).longValue();}
}
