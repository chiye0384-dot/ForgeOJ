/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/operations")
public class OperationsController {
    private final OperationsService service;
    private final AdminService admins;
    private final OperationsRecoveryService recovery;
    public OperationsController(OperationsService service,AdminService admins,OperationsRecoveryService recovery){this.service=service;this.admins=admins;this.recovery=recovery;}
    @GetMapping("/tasks") ResponseEntity<?> list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String kind,@RequestParam(required=false)String id,@RequestParam(required=false)String status){limit();return ok(service.list(page,size,kind,id,status));}
    @GetMapping("/tasks/{kind}/{id}") ResponseEntity<?> detail(@PathVariable String kind,@PathVariable String id){limit();return ok(service.detail(kind,id));}
    @GetMapping("/tasks/{kind}/{id}/attempts") ResponseEntity<?> attempts(@PathVariable String kind,@PathVariable String id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){limit();return ok(service.attempts(kind,id,page,size));}
    @GetMapping("/tasks/{kind}/{id}/events") ResponseEntity<?> events(@PathVariable String kind,@PathVariable String id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){limit();return ok(service.events(kind,id,page,size));}
    @GetMapping("/tasks/{kind}/{id}/recoveries") ResponseEntity<?> recoveries(@PathVariable String kind,@PathVariable String id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){limit();return ok(service.recoveries(kind,id,page,size));}
    @PostMapping("/tasks/{kind}/{id}/retry") ResponseEntity<?> retry(@PathVariable String kind,@PathVariable String id,@RequestBody Map<String,Object> body){limit();return ok(recovery.recover(kind,id,null,input(body,false)));}
    @PostMapping("/tasks/{kind}/{id}/events/{event}/recover") ResponseEntity<?> delivery(@PathVariable String kind,@PathVariable String id,@PathVariable String event,@RequestBody Map<String,Object> body){limit();return ok(recovery.recover(kind,id,event,input(body,true)));}
    private OperationsRecoveryService.Input input(Map<String,Object> body,boolean delivery){
        var keys=delivery?Set.of("expectedVersion","clientRequestId","reason","expectedPublishAttempts"):Set.of("expectedVersion","clientRequestId","reason");
        if(!body.keySet().equals(keys)||!(body.get("expectedVersion") instanceof Integer||body.get("expectedVersion") instanceof Long)||!(body.get("clientRequestId") instanceof String request)||!(body.get("reason") instanceof String reason))throw AdminInput.error(400);
        Integer attempts=null;
        if(delivery){if(!(body.get("expectedPublishAttempts") instanceof Integer number)||number<0)throw AdminInput.error(400);attempts=number;}
        return new OperationsRecoveryService.Input(((Number)body.get("expectedVersion")).longValue(),request,reason,attempts);
    }
    private ResponseEntity<?> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    private void limit(){var p=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();if(p instanceof AdminPrincipal a)admins.rate("ops-actor:"+a.id(),120,300);}
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<Void> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<Void> rejected(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();}
}
