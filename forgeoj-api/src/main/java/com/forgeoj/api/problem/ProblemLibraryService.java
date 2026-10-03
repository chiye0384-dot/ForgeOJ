/*
 * Copyright 2026 池也
 * SPDX-License-Identifier: Apache-2.0
 */
package com.forgeoj.api.problem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProblemLibraryService {

    private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
    private final ProblemLibraryMapper mapper;

    public ProblemLibraryService(ProblemLibraryMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProblemPage list(String keyword, String difficulty, String tag, int page, int size) {
        if (page < 1 || size < 1 || size > 50) {
            throw invalidQuery();
        }
        String titleKeyword = normalized(keyword, 100);
        String selectedDifficulty = normalized(difficulty, 8);
        String selectedTag = normalized(tag, 32);
        if (selectedDifficulty != null && !DIFFICULTIES.contains(selectedDifficulty)) {
            throw invalidQuery();
        }
        String pattern = titleKeyword == null ? null : "%" + titleKeyword
                .replace("=", "==").replace("%", "=%").replace("_", "=_") + "%";
        long total = mapper.count(pattern, selectedDifficulty, selectedTag);
        long offset = ((long) page - 1) * size;
        if (offset >= total) {
            return new ProblemPage(List.of(), page, size, total);
        }
        List<ProblemLibraryMapper.ProblemSummaryRow> rows =
                mapper.page(pattern, selectedDifficulty, selectedTag, size, offset);
        Map<Long, List<String>> tagsByProblem = new HashMap<>();
        if (!rows.isEmpty()) {
            for (var problemTag : mapper.tagsForProblems(rows.stream().map(row -> row.id()).toList())) {
                tagsByProblem.computeIfAbsent(problemTag.problemId(), ignored -> new ArrayList<>())
                        .add(problemTag.tag());
            }
        }
        List<ProblemSummary> items = rows.stream().map(row -> new ProblemSummary(
                row.slug(), row.title(), row.difficulty(),
                List.copyOf(tagsByProblem.getOrDefault(row.id(), List.of())), row.judgeVersion()))
                .toList();
        return new ProblemPage(items, page, size, total);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProblemTags tags() {
        return new ProblemTags(List.copyOf(mapper.availableTags()));
    }

    private static String normalized(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.codePointCount(0, normalized.length()) > maximumLength) {
            throw invalidQuery();
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private static ResponseStatusException invalidQuery() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    public record ProblemSummary(String slug, String title, String difficulty, List<String> tags,
            int judgeVersion) {}

    public record ProblemPage(List<ProblemSummary> items, int page, int size, long total) {}

    public record ProblemTags(List<String> tags) {}
}
