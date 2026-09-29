package com.forgeoj.worker.messaging;

import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
final class JudgeTaskMessageParser {

    private final ObjectReader reader;

    JudgeTaskMessageParser(ObjectMapper objectMapper) {
        this.reader =
                objectMapper
                        .readerFor(JudgeTaskMessage.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    JudgeTaskMessage parse(byte[] body) {
        try {
            JudgeTaskMessage message = reader.readValue(body);
            validate(message);
            return message;
        } catch (JacksonException failure) {
            throw new InvalidJudgeTaskMessageException(failure);
        }
    }

    private void validate(JudgeTaskMessage message) {
        if (message == null
                || !isCanonicalUuid(message.taskId())
                || !isCanonicalUuid(message.submissionId())
                || !"JUDGE_SUBMISSION".equals(message.taskType())
                || message.contractVersion() != 1) {
            throw new InvalidJudgeTaskMessageException("Unsupported judge task contract");
        }
    }

    private boolean isCanonicalUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
