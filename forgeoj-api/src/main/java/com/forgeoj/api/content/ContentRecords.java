/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import java.util.List;

public final class ContentRecords {
    private ContentRecords() {}
    public record Sample(String input,String output) {}
    public record Metadata(String title,String statement,String inputDescription,String outputDescription,
            List<Sample> samples,String originType,String sourceUrl,String licenseStatement,
            int timeLimitMs,int memoryLimitMb,long outputLimitBytes) {}
    public record Content(Metadata metadata,String referenceCode,String solutionIdea,String solutionCode) {}
    public record Summary(String id,String title,long version,String status,int testCount) {}
    public record Detail(Summary draft,Content content) {}
    public record TestCase(int sequence,String input,String expectedOutput) {}
    public record Page(List<Summary> items,int page,int size,long total) {}
}
