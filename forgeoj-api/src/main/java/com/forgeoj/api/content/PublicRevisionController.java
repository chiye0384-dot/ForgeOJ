/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;
import java.util.Map;
import com.forgeoj.api.auth.ForgeOjPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import static com.forgeoj.api.admin.PublicContentInput.*;

@RestController
@RequestMapping("/api/v1/me/public-problems")
public class PublicRevisionController {
    private final PublicRevisionService service;
    public PublicRevisionController(PublicRevisionService service){this.service=service;}
    @GetMapping ResponseEntity<PublicRevisionService.Page> list(@AuthenticationPrincipal ForgeOjPrincipal p,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(p.userId(),page,size));}
    @PostMapping("/{slug}/revisions") ResponseEntity<ContentRecords.Detail> copy(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String slug,@RequestBody Map<String,Object> body){
        keys(body,"expectedVersion","revisionKind","clientRequestId");return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.copy(p.userId(),slug,integer(body,"expectedVersion"),string(body,"revisionKind"),string(body,"clientRequestId")));
    }
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<Void> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<Void> failure(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();}
}
