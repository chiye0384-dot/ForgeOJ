package com.forgeoj.worker.snapshot;

record HiddenTestCaseRow(
        int ordinal,
        byte[] inputDataGzip,
        byte[] expectedOutputGzip,
        long inputSizeBytes,
        long outputSizeBytes,
        String inputSha256,
        String outputSha256) {}
