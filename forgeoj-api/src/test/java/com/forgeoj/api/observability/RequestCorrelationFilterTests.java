package com.forgeoj.api.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTests {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestCorrelationFilter.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeEach void start() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach void stop() {
        logger.detachAppender(logs);
        logs.stop();
        MDC.clear();
    }

    @Test void generatesInternalIdAndNeverLogsRequestSecrets() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/v1/problems/SLUG_SENTINEL/submissions");
        request.addHeader("X-Request-Id", "FORGED_SENTINEL");
        request.addHeader("Authorization", "PASSWORD_SENTINEL");
        request.addHeader("Cookie", "SESSION_SENTINEL");
        request.setQueryString("token=QUERY_SENTINEL");
        request.setContent("SOURCE_HIDDEN_INPUT_SENTINEL".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            seen.set(MDC.get("requestId"));
            response.setStatus(202);
        });
        assertThat(UUID.fromString(seen.get()).toString()).isEqualTo(seen.get());
        assertThat(MDC.get("requestId")).isNull();
        Map<String, Object> fields = fields(logs.list.getFirst());
        assertThat(fields).containsOnlyKeys("event", "method", "route", "httpStatus", "durationMillis")
                .containsEntry("route", "submission.create")
                .containsEntry("httpStatus", 202);
        assertThat(logs.list.getFirst().getMDCPropertyMap()).containsEntry("requestId", seen.get());
        assertThat(fields.toString()).doesNotContain("SENTINEL");
        assertThat(response.getHeader("X-Request-Id")).isNull();
    }

    @Test void restoresPreviousContextAndUsesNewIdOnReusedThread() throws Exception {
        MDC.put("requestId", "prior-internal-context");
        for (int i = 0; i < 2; i++) {
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/submissions/opaque"),
                    new MockHttpServletResponse(), (request, response) -> {});
            assertThat(MDC.get("requestId")).isEqualTo("prior-internal-context");
        }
        assertThat(logs.list.get(0).getMDCPropertyMap().get("requestId"))
                .isNotEqualTo(logs.list.get(1).getMDCPropertyMap().get("requestId"));
    }

    @Test void thrownFailureDoesNotLeakExceptionAndStillCleansContext() {
        assertThatThrownBy(() -> filter.doFilter(
                new MockHttpServletRequest("UNKNOWN_SENTINEL", "/SECRET_SENTINEL"),
                new MockHttpServletResponse(), (request, response) -> {
                    throw new IOException("PASSWORD_SOURCE_SENTINEL");
                })).isInstanceOf(IOException.class);
        assertThat(MDC.get("requestId")).isNull();
        assertThat(fields(logs.list.getFirst())).containsEntry("httpStatus", 500)
                .containsEntry("method", "OTHER").containsEntry("route", "other");
        assertThat(fields(logs.list.getFirst()).toString()).doesNotContain("SENTINEL");
        assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }
}
