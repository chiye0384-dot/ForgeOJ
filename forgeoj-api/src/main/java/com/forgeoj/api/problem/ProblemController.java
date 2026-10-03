package com.forgeoj.api.problem;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/problems")
public class ProblemController {

    private final ProblemMapper problemMapper;

    public ProblemController(ProblemMapper problemMapper) {
        this.problemMapper = problemMapper;
    }

    @GetMapping("/{slug}")
    ProblemResponse getProblem(@PathVariable String slug) {
        ProblemDetailsRow problem =
                problemMapper
                        .findActiveBySlug(slug)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Problem not found"));

        return new ProblemResponse(
                problem.slug(),
                problem.title(),
                problem.statementText(),
                problem.inputDescription(),
                problem.outputDescription(),
                List.of(new PublicSampleResponse(problem.sampleInput(), problem.sampleOutput())),
                problem.judgeVersion(),
                new ResourceLimitsResponse(
                        problem.timeLimitMs(),
                        problem.memoryLimitMb(),
                        problem.outputLimitBytes()));
    }

    public record ProblemResponse(
            String slug,
            String title,
            String statement,
            String inputDescription,
            String outputDescription,
            List<PublicSampleResponse> publicSamples,
            int judgeVersion,
            ResourceLimitsResponse resourceLimits) {}

    public record PublicSampleResponse(String input, String output) {}

    public record ResourceLimitsResponse(
            int timeLimitMs, int memoryLimitMb, long outputLimitBytes) {}

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
