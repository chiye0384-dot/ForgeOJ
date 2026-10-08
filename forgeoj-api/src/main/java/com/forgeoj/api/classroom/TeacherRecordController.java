/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.classroom;

import com.forgeoj.api.auth.ForgeOjPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/classrooms/{room}/assignments/{id}/teaching")
public class TeacherRecordController {
    private final TeacherRecordService service;
    public TeacherRecordController(TeacherRecordService service) {this.service=service;}
    @GetMapping("/grades") ResponseEntity<?> grades(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String room,@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {return result(service.grades(p.userId(),room,id,page,size));}
    @GetMapping("/participants/{userId}/problems/{ordinal}/attempts") ResponseEntity<?> attempts(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String room,@PathVariable String id,@PathVariable long userId,@PathVariable int ordinal,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {return result(service.attempts(p.userId(),room,id,userId,ordinal,page,size));}
    @GetMapping("/submissions/{submissionId}") ResponseEntity<?> source(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String room,@PathVariable String id,@PathVariable String submissionId) {return result(service.source(p.userId(),room,id,submissionId));}
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<?> unavailable() {return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> rejected(ResponseStatusException e) {return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();}
    private static ResponseEntity<?> result(Object body) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
