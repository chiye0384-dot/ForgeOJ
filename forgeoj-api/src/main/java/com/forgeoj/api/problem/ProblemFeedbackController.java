/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.problem;
import com.forgeoj.api.admin.PublicFeedbackService;
import java.util.Map;
import static com.forgeoj.api.admin.PublicContentInput.*;
import com.forgeoj.api.auth.ForgeOjPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/v1/problems/{slug}/feedbacks")
public class ProblemFeedbackController {
    private final PublicFeedbackService service;
    public record Report(String clientRequestId,String category,String body) {}
    public ProblemFeedbackController(PublicFeedbackService service){this.service=service;}
    @GetMapping ResponseEntity<Object> mine(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String slug,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.mine(p.userId(),slug,page,size));}
    @PostMapping ResponseEntity<Object> report(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String slug,@RequestBody Map<String,Object> body){keys(body,"clientRequestId","category","body");return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.report(p.userId(),slug,string(body,"clientRequestId"),string(body,"category"),string(body,"body")));}
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<Void> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<Void> rejected(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();}
}
