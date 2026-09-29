package com.forgeoj.worker.snapshot;

import com.forgeoj.worker.messaging.JudgeTaskMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JudgeTaskSnapshotLoader {

    private static final long MAX_TEST_FILE_BYTES = 16L * 1024L * 1024L;
    private static final int COPY_BUFFER_BYTES = 8192;

    private final JudgeTaskSnapshotMapper mapper;

    JudgeTaskSnapshotLoader(JudgeTaskSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public JudgeTaskSnapshot load(JudgeTaskMessage message) {
        JudgeTaskSnapshotRow row =
                mapper.findRunningSnapshot(
                                message.taskId(),
                                message.submissionId(),
                                message.taskType(),
                                message.contractVersion())
                        .orElseThrow(
                                () ->
                                        new JudgeTaskSnapshotException(
                                                "No matching running task snapshot"));

        validateSnapshot(row);
        List<HiddenTestCaseRow> hiddenRows = mapper.findHiddenTestCases(row.judgeVersionId());
        if (hiddenRows.isEmpty()) {
            throw new JudgeTaskSnapshotException("Running task has no hidden test cases");
        }

        List<JudgeTestCase> testCases = new ArrayList<>(hiddenRows.size());
        StringBuilder datasetManifest = new StringBuilder();
        int expectedOrdinal = 1;
        for (HiddenTestCaseRow hidden : hiddenRows) {
            if (hidden.ordinal() != expectedOrdinal) {
                throw new JudgeTaskSnapshotException("Hidden test case order is not contiguous");
            }
            byte[] input = decodeAndVerify(hidden, true);
            byte[] expectedOutput = decodeAndVerify(hidden, false);
            testCases.add(new JudgeTestCase(hidden.ordinal(), input, expectedOutput));
            datasetManifest
                    .append(hidden.ordinal())
                    .append(':')
                    .append(hidden.inputSha256())
                    .append(':')
                    .append(hidden.outputSha256())
                    .append('\n');
            expectedOrdinal++;
        }
        String actualDatasetHash =
                sha256(datasetManifest.toString().getBytes(StandardCharsets.US_ASCII));
        if (!actualDatasetHash.equals(row.testDatasetSha256())) {
            throw new JudgeTaskSnapshotException("Hidden test dataset integrity check failed");
        }

        return new JudgeTaskSnapshot(
                row.taskId(),
                row.submissionId(),
                row.judgeVersionId(),
                row.language(),
                row.sourceCode(),
                row.sourceSha256(),
                row.timeLimitMs(),
                row.memoryLimitMb(),
                row.outputLimitBytes(),
                row.comparisonRuleVersion(),
                row.sandboxPolicyVersion(),
                row.javaImageDigest(),
                row.testDatasetSha256(),
                testCases);
    }

    private void validateSnapshot(JudgeTaskSnapshotRow row) {
        if (!"JAVA_21".equals(row.language())) {
            throw new JudgeTaskSnapshotException("Unsupported snapshot language");
        }
        if (row.timeLimitMs() <= 0
                || row.memoryLimitMb() <= 0
                || row.outputLimitBytes() <= 0) {
            throw new JudgeTaskSnapshotException("Invalid snapshot resource limits");
        }
        if (!row.testDatasetSha256().equals(row.storedTestDatasetSha256())) {
            throw new JudgeTaskSnapshotException("Test dataset identity does not match snapshot");
        }
        String actualSourceHash = sha256(row.sourceCode().getBytes(StandardCharsets.UTF_8));
        if (!actualSourceHash.equals(row.sourceSha256())) {
            throw new JudgeTaskSnapshotException("Submission source integrity check failed");
        }
    }

    private byte[] decodeAndVerify(HiddenTestCaseRow row, boolean input) {
        byte[] compressed = input ? row.inputDataGzip() : row.expectedOutputGzip();
        long expectedSize = input ? row.inputSizeBytes() : row.outputSizeBytes();
        String expectedHash = input ? row.inputSha256() : row.outputSha256();
        if (expectedSize < 0 || expectedSize > MAX_TEST_FILE_BYTES) {
            throw new JudgeTaskSnapshotException("Hidden test case size is outside M0 limits");
        }

        byte[] decoded;
        try {
            decoded = gunzipBounded(compressed, expectedSize);
        } catch (IOException failure) {
            throw new JudgeTaskSnapshotException("Hidden test case decompression failed", failure);
        }
        if (decoded.length != expectedSize || !sha256(decoded).equals(expectedHash)) {
            throw new JudgeTaskSnapshotException("Hidden test case integrity check failed");
        }
        return decoded;
    }

    private byte[] gunzipBounded(byte[] compressed, long expectedSize) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed));
                ByteArrayOutputStream output =
                        new ByteArrayOutputStream((int) Math.min(expectedSize, COPY_BUFFER_BYTES))) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            long maximum = expectedSize + 1;
            while (output.size() < maximum) {
                int remaining = (int) Math.min(buffer.length, maximum - output.size());
                int read = input.read(buffer, 0, remaining);
                if (read == -1) {
                    break;
                }
                output.write(buffer, 0, read);
            }
            if (output.size() > expectedSize || input.read() != -1) {
                throw new IOException("Decompressed data exceeds declared size");
            }
            return output.toByteArray();
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
