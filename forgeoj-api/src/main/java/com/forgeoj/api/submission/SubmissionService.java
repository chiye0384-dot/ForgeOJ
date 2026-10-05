package com.forgeoj.api.submission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SubmissionService {

    static final int MAX_SOURCE_BYTES = 64 * 1024;

    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("(?m)^\\s*package\\s+[\\p{L}_$]");
    private static final Pattern PUBLIC_MAIN_CLASS =
            Pattern.compile("\\bpublic\\s+(?:final\\s+)?class\\s+Main\\b");

    private final SubmissionMapper submissionMapper;
    private final SubmissionTransactionService transactionService;

    public SubmissionService(
            SubmissionMapper submissionMapper, SubmissionTransactionService transactionService) {
        this.submissionMapper = submissionMapper;
        this.transactionService = transactionService;
    }

    public SubmissionResult create(
            long userId,
            String problemSlug,
            String idempotencyKey,
            String language,
            String sourceCode) {
        UUID clientRequestId = parseIdempotencyKey(idempotencyKey);
        validateLanguage(language);
        validateSource(sourceCode);
        if (!submissionMapper.isActiveUser(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is not active");
        }

        SubmissionResult existing =
                submissionMapper
                        .findResultByRequest(userId, clientRequestId.toString())
                        .orElse(null);
        if (existing != null) {
            return existing;
        }

        try {
            return transactionService.createNew(
                    userId,
                    problemSlug,
                    clientRequestId,
                    language,
                    sourceCode,
                    sha256(sourceCode));
        } catch (DuplicateKeyException duplicate) {
            return submissionMapper
                    .findResultByRequest(userId, clientRequestId.toString())
                    .orElseThrow(() -> duplicate);
        }
    }

    private UUID parseIdempotencyKey(String value) {
        if (value == null) {
            throw badRequest("Idempotency-Key is required");
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw badRequest("Idempotency-Key must be a canonical UUID");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw badRequest("Idempotency-Key must be a UUID");
        }
    }

    private void validateLanguage(String language) {
        if (!"JAVA_21".equals(language)) {
            throw badRequest("M0 only accepts JAVA_21");
        }
    }

    public static void validateSource(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw badRequest("sourceCode is required");
        }
        if (sourceCode.indexOf('\0') >= 0) {
            throw badRequest("sourceCode contains an unsupported null character");
        }
        if (sourceCode.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw badRequest("sourceCode exceeds the 65536-byte M0 limit");
        }
        String codeOnly = codeOnly(sourceCode);
        if (PACKAGE_DECLARATION.matcher(codeOnly).find()) {
            throw badRequest("M0 Main.java must not declare a package");
        }
        if (!PUBLIC_MAIN_CLASS.matcher(codeOnly).find()) {
            throw badRequest("M0 sourceCode must declare public class Main");
        }
    }

    private static String codeOnly(String sourceCode) {
        StringBuilder result = new StringBuilder(sourceCode.length());
        ScanState state = ScanState.CODE;

        for (int index = 0; index < sourceCode.length(); index++) {
            char current = sourceCode.charAt(index);
            char next = index + 1 < sourceCode.length() ? sourceCode.charAt(index + 1) : '\0';

            if (state == ScanState.CODE) {
                if (current == '/' && next == '/') {
                    result.append("  ");
                    index++;
                    state = ScanState.LINE_COMMENT;
                } else if (current == '/' && next == '*') {
                    result.append("  ");
                    index++;
                    state = ScanState.BLOCK_COMMENT;
                } else if (current == '"'
                        && next == '"'
                        && index + 2 < sourceCode.length()
                        && sourceCode.charAt(index + 2) == '"') {
                    result.append("   ");
                    index += 2;
                    state = ScanState.TEXT_BLOCK;
                } else if (current == '"') {
                    result.append(' ');
                    state = ScanState.STRING;
                } else if (current == '\'') {
                    result.append(' ');
                    state = ScanState.CHARACTER;
                } else {
                    result.append(current);
                }
                continue;
            }

            if (state == ScanState.LINE_COMMENT) {
                if (current == '\n' || current == '\r') {
                    result.append(current);
                    state = ScanState.CODE;
                } else {
                    result.append(' ');
                }
                continue;
            }

            if (state == ScanState.BLOCK_COMMENT) {
                if (current == '*' && next == '/') {
                    result.append("  ");
                    index++;
                    state = ScanState.CODE;
                } else {
                    result.append(current == '\n' || current == '\r' ? current : ' ');
                }
                continue;
            }

            if (state == ScanState.TEXT_BLOCK
                    && current == '"'
                    && next == '"'
                    && index + 2 < sourceCode.length()
                    && sourceCode.charAt(index + 2) == '"') {
                result.append("   ");
                index += 2;
                state = ScanState.CODE;
                continue;
            }

            if ((state == ScanState.STRING || state == ScanState.CHARACTER) && current == '\\') {
                result.append(' ');
                if (index + 1 < sourceCode.length()) {
                    char escaped = sourceCode.charAt(++index);
                    result.append(escaped == '\n' || escaped == '\r' ? escaped : ' ');
                }
                continue;
            }

            boolean closesLiteral =
                    (state == ScanState.STRING && current == '"')
                            || (state == ScanState.CHARACTER && current == '\'');
            result.append(current == '\n' || current == '\r' ? current : ' ');
            if (closesLiteral) {
                state = ScanState.CODE;
            }
        }
        return result.toString();
    }

    private String sha256(String sourceCode) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(sourceCode.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private enum ScanState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        CHARACTER,
        TEXT_BLOCK
    }
}
