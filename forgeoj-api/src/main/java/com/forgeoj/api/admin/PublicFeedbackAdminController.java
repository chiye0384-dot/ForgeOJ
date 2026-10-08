/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static com.forgeoj.api.admin.PublicContentInput.*;
import org.springframework.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/v1/admin/feedback-cases")
public class PublicFeedbackAdminController {
    private final PublicFeedbackService service;
    private final AdminService admins;
    public record Close(long expectedVersion,String reason) {}
    public PublicFeedbackAdminController(PublicFeedbackService service,AdminService admins){this.service=service;this.admins=admins;}
    @GetMapping Object list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String status){limit();return service.cases(page,size,status);}
    @GetMapping("/{id}") Object detail(@PathVariable String id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){limit();return service.detail(id,page,size);}
    @PostMapping("/{id}/close") Object close(@PathVariable String id,@RequestBody Map<String,Object> body){limit();keys(body,"expectedVersion","reason");return service.close(id,integer(body,"expectedVersion"),string(body,"reason"));}
    private void limit(){var p=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();if(p instanceof AdminPrincipal a)admins.rate("content-actor:"+a.id(),120,300);}
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<Void> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<Void> rejected(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();}
}
