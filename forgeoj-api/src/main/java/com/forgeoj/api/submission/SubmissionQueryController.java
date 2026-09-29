package com.forgeoj.api.submission;

import com.forgeoj.api.auth.ForgeOjPrincipal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionQueryController {

    private final SubmissionQueryService submissionQueryService;

    public SubmissionQueryController(SubmissionQueryService submissionQueryService) {
        this.submissionQueryService = submissionQueryService;
    }

    @GetMapping("/{submissionId}")
    SubmissionStatus get(
            @PathVariable String submissionId,
            @AuthenticationPrincipal ForgeOjPrincipal principal) {
        return submissionQueryService.getForOwner(principal.userId(), submissionId);
    }
}
