/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/v1/admin/search")
public class SearchAdministrationController {
    private final SearchAdministrationService service;private final AdminService admins;
    public SearchAdministrationController(SearchAdministrationService service,AdminService admins){this.service=service;this.admins=admins;}
    @GetMapping("/status") ResponseEntity<?> status(){limit();return ok(service.status());}
    @GetMapping("/rebuilds") ResponseEntity<?> list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){limit();return ok(service.list(page,size));}
    @PostMapping("/rebuilds") ResponseEntity<?> rebuild(@RequestBody Map<String,Object> body){limit();if(!body.keySet().equals(Set.of("expectedVersion","clientRequestId","reason"))||!(body.get("expectedVersion") instanceof Integer||body.get("expectedVersion") instanceof Long)||!(body.get("clientRequestId") instanceof String request)||!(body.get("reason") instanceof String reason))throw AdminInput.error(400);return ok(service.rebuild(((Number)body.get("expectedVersion")).longValue(),request,reason));}
    private ResponseEntity<?> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    private void limit(){var p=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();if(p instanceof AdminPrincipal a)admins.rate("ops-actor:"+a.id(),120,300);}
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<Void> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<Void> rejected(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();}
}
