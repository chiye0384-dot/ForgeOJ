package com.forgeoj.api.problem;

public record ProblemDetailsRow(
        String slug,
        String title,
        String statementText,
        String inputDescription,
        String outputDescription,
        String sampleInput,
        String sampleOutput,
        int judgeVersion,
        int timeLimitMs,
        int memoryLimitMb,
        long outputLimitBytes) {}
