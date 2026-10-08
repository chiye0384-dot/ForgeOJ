/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import com.forgeoj.api.content.ContentValidationMapper;
import java.util.Map;
import static com.forgeoj.api.admin.PublicContentInput.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin")
public class PublicReviewController {
    public record Decision(long expectedVersion,String clientRequestId,String reason) {}
    public record State(long expectedVersion,String reason) {}
    private final PublicReviewService service;
    private final AdminService admins;
    public PublicReviewController(PublicReviewService service,AdminService admins){this.service=service;this.admins=admins;}
    @GetMapping("/content-reviews") Object list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String status){limit();return service.list(page,size,status);}
    @GetMapping("/content-reviews/{id}") PublicReviewService.Detail detail(@PathVariable String id){limit();return service.detail(id);}
    @GetMapping("/content-reviews/{id}/reference") PublicReviewService.Reference reference(@PathVariable String id){limit();return service.reference(id);}
    @PostMapping("/content-reviews/{id}/approve") PublicReviewMapper.Review approve(@PathVariable String id,@RequestBody Map<String,Object> body){limit();Decision d=decision(body);return service.decide(id,d.expectedVersion(),"APPROVED",d.clientRequestId(),d.reason());}
    @PostMapping("/content-reviews/{id}/reject") PublicReviewMapper.Review reject(@PathVariable String id,@RequestBody Map<String,Object> body){limit();Decision d=decision(body);return service.decide(id,d.expectedVersion(),"REJECTED",d.clientRequestId(),d.reason());}
    @PostMapping("/content-reviews/{id}/recheck") @ResponseStatus(HttpStatus.ACCEPTED) ContentValidationMapper.Result recheck(@PathVariable String id,@RequestBody Map<String,Object> body){limit();Decision d=decision(body);return service.recheck(id,d.expectedVersion(),d.clientRequestId(),d.reason());}
    @GetMapping("/public-problems") Object problems(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String status){limit();return service.problems(page,size,status);}
    @PostMapping("/public-problems/{id}/archive") PublicReviewMapper.Problem archive(@PathVariable long id,@RequestBody Map<String,Object> body){limit();State d=state(body);return service.state(id,d.expectedVersion(),"ARCHIVE",d.reason());}
    @PostMapping("/public-problems/{id}/restore") PublicReviewMapper.Problem restore(@PathVariable long id,@RequestBody Map<String,Object> body){limit();State d=state(body);return service.state(id,d.expectedVersion(),"RESTORE",d.reason());}
    @PostMapping("/public-problems/{id}/invalidate") PublicReviewMapper.Problem invalidate(@PathVariable long id,@RequestBody Map<String,Object> body){limit();State d=state(body);return service.state(id,d.expectedVersion(),"INVALIDATE",d.reason());}
    private static Decision decision(Map<String,Object> body){keys(body,"expectedVersion","clientRequestId","reason");return new Decision(integer(body,"expectedVersion"),string(body,"clientRequestId"),string(body,"reason"));}
    private static State state(Map<String,Object> body){keys(body,"expectedVersion","reason");return new State(integer(body,"expectedVersion"),string(body,"reason"));}
    private void limit(){var p=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();if(p instanceof AdminPrincipal a)admins.rate("content-actor:"+a.id(),120,300);}
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<Void> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<Void> rejected(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();}
}
