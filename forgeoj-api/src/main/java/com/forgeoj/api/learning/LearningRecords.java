/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.learning;

import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

public final class LearningRecords {
    private LearningRecords() {}
    public record Page<T>(List<T> items, int page, int size, long total) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListSummary(String id, String title, String description, long version,
            long entryCount, long availableCount, Long completedCount, long unavailableCount) {}
    public record ListDetail(ListSummary list, List<Entry> items, int page, int size, long total) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String itemId, int position, boolean available, PublicProblem problem) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PublicProblem(String slug, String title, String difficulty, List<String> tags,
            int judgeVersion, Boolean completed) {}
    public record HistoryProblem(String slug, String title) {}
    public record History(String submissionId, LocalDateTime createdAt, String language,
            String processingStatus, long statusVersion, String verdict, int judgeVersion,
            HistoryProblem problem,
            @JsonInclude(JsonInclude.Include.NON_NULL) String judgeDataWarning) {}
    public record Draft(String language, String sourceCode, long version, LocalDateTime updatedAt,
            boolean editable) {}
    public record TitleBody(String title, Long expectedVersion) {}
    public record AddBody(String problemSlug, Long expectedVersion) {}
    public record OrderBody(List<String> itemIds, Long expectedVersion) {}
    public record DraftBody(String language, String sourceCode, Long expectedVersion) {}
}
