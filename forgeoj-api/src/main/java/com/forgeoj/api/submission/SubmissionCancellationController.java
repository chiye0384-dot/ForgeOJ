package com.forgeoj.api.submission;

import com.forgeoj.api.auth.ForgeOjPrincipal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionCancellationController {

    private final SubmissionCancellationService service;

    public SubmissionCancellationController(SubmissionCancellationService service) {
        this.service = service;
    }

    @PostMapping("/{submissionId}/cancel")
    SubmissionResult cancel(
            @PathVariable String submissionId,
            @AuthenticationPrincipal ForgeOjPrincipal principal) {
        return service.cancelForOwner(principal.userId(), submissionId);
    }
}
