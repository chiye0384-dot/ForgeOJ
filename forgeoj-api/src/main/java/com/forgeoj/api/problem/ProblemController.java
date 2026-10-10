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
    private final tools.jackson.databind.ObjectMapper json;
    private final com.forgeoj.api.cache.PublicCache cache;

    public ProblemController(ProblemMapper problemMapper,tools.jackson.databind.ObjectMapper json,com.forgeoj.api.cache.PublicCache cache) {
        this.problemMapper = problemMapper;
        this.json=json;
        this.cache=cache;
    }

    @GetMapping("/{slug}")
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    ProblemResponse getProblem(@PathVariable String slug) {
        if(slug.length()>128)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return cache.read("problem:"+slug,ProblemResponse.class,()->load(slug));
    }
    private ProblemResponse load(String slug) {
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
                java.util.Arrays.asList(json.readValue(problem.publicSamplesJson(),PublicSampleResponse[].class)),
                problem.judgeVersion(),
                new ResourceLimitsResponse(
                        problem.timeLimitMs(),
                        problem.memoryLimitMb(),
                        problem.outputLimitBytes()),
                problem.authorName()==null?null:new Attribution(problem.authorName(),problem.originType(),problem.sourceUrl(),problem.licenseStatement(),problem.correctionOfSlug()));
    }

    public record ProblemResponse(
            String slug,
            String title,
            String statement,
            String inputDescription,
            String outputDescription,
            List<PublicSampleResponse> publicSamples,
            int judgeVersion,
            ResourceLimitsResponse resourceLimits,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Attribution attribution) {}
    public record Attribution(String authorName,String originType,String sourceUrl,String licenseStatement,String correctionOfSlug) {}

    public record PublicSampleResponse(String input, String output) {}

    public record ResourceLimitsResponse(
            int timeLimitMs, int memoryLimitMb, long outputLimitBytes) {}

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
