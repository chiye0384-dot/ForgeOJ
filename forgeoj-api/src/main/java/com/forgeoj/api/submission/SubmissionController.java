package com.forgeoj.api.submission;

import com.forgeoj.api.auth.ForgeOjPrincipal;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/problems")
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping("/{slug}/submissions")
    ResponseEntity<SubmissionResult> create(
            @PathVariable String slug,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SubmissionRequest request,
            @AuthenticationPrincipal ForgeOjPrincipal principal) {
        SubmissionResult result =
                submissionService.create(
                        principal.userId(),
                        slug,
                        idempotencyKey,
                        request.language(),
                        request.sourceCode());
        return ResponseEntity.accepted().body(result);
    }

    record SubmissionRequest(String language, String sourceCode) {}
}
