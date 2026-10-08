package com.forgeoj.api.submission;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SubmissionQueryService {

    private static final int DIAGNOSTIC_LIMIT = 2000;
    private static final String SYSTEM_ERROR_DIAGNOSTIC = "Judging infrastructure failed";

    private final SubmissionMapper submissionMapper;

    public SubmissionQueryService(SubmissionMapper submissionMapper) {
        this.submissionMapper = submissionMapper;
    }

    public SubmissionStatus getForOwner(long userId, String submissionId) {
        SubmissionStatus stored =
                submissionMapper
                        .findStatusByOwner(userId, submissionId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Submission not found"));

        String verdict = "FINISHED".equals(stored.processingStatus()) ? stored.verdict() : null;
        return new SubmissionStatus(
                stored.submissionId(),
                stored.processingStatus(),
                stored.statusVersion(),
                verdict,
                publicDiagnostic(stored),stored.judgeDataWarning());
    }

    private String publicDiagnostic(SubmissionStatus stored) {
        if ("SYSTEM_ERROR".equals(stored.processingStatus())) {
            return SYSTEM_ERROR_DIAGNOSTIC;
        }
        if (!"FINISHED".equals(stored.processingStatus()) || !"CE".equals(stored.verdict())) {
            return null;
        }
        return sanitizeCompilerDiagnostic(stored.diagnosticMessage());
    }

    private String sanitizeCompilerDiagnostic(String diagnostic) {
        if (diagnostic == null || diagnostic.isBlank()) {
            return null;
        }
        StringBuilder sanitized = new StringBuilder(Math.min(diagnostic.length(), DIAGNOSTIC_LIMIT));
        for (int index = 0; index < diagnostic.length(); ) {
            int value = diagnostic.codePointAt(index);
            int charCount = Character.charCount(value);
            index += charCount;
            if (sanitized.length() + charCount > DIAGNOSTIC_LIMIT) {
                break;
            }
            if (!Character.isISOControl(value) || value == '\n' || value == '\r' || value == '\t') {
                sanitized.appendCodePoint(value);
            }
        }
        return sanitized.isEmpty() ? null : sanitized.toString();
    }
}
