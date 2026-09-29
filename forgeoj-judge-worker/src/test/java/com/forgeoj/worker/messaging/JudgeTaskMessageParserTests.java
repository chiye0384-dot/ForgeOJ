package com.forgeoj.worker.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class JudgeTaskMessageParserTests {

    private final JudgeTaskMessageParser parser =
            new JudgeTaskMessageParser(JsonMapper.builder().build());

    @Test
    void acceptsTheExactVersionOneContract() {
        String taskId = "64d98bf3-a4b2-4713-87c9-975e46c3d138";
        String submissionId = "2e6626f1-a59d-49f2-b3fa-648b8c288147";

        assertThat(
                        parser.parse(
                                message(
                                                taskId,
                                                submissionId,
                                                "\"taskType\":\"JUDGE_SUBMISSION\","
                                                        + "\"contractVersion\":1")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(
                        new JudgeTaskMessage(
                                taskId, submissionId, "JUDGE_SUBMISSION", 1));
    }

    @Test
    void rejectsUnknownMissingAndInvalidFields() {
        String taskId = "64d98bf3-a4b2-4713-87c9-975e46c3d138";
        String submissionId = "2e6626f1-a59d-49f2-b3fa-648b8c288147";

        assertThatThrownBy(
                        () ->
                                parser.parse(
                                        message(
                                                        taskId,
                                                        submissionId,
                                                        "\"taskType\":\"JUDGE_SUBMISSION\","
                                                                + "\"contractVersion\":1,"
                                                                + "\"sourceCode\":\"secret\"")
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidJudgeTaskMessageException.class);
        assertThatThrownBy(
                        () ->
                                parser.parse(
                                        message(
                                                        taskId,
                                                        submissionId,
                                                        "\"contractVersion\":1")
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidJudgeTaskMessageException.class);
        assertThatThrownBy(
                        () ->
                                parser.parse(
                                        message(
                                                        "not-a-uuid",
                                                        submissionId,
                                                        "\"taskType\":\"JUDGE_SUBMISSION\","
                                                                + "\"contractVersion\":1")
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidJudgeTaskMessageException.class);
    }

    private String message(String taskId, String submissionId, String remainingFields) {
        return """
                {"taskId":"%s","submissionId":"%s",%s}
                """
                .formatted(taskId, submissionId, remainingFields)
                .strip();
    }
}
