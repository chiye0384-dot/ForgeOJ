package com.forgeoj.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SandboxRecoveryCleanerTests {

    @Test void scheduledSweepUsesOwnershipAwareRuntime() {
        SandboxRuntime runtime = mock(SandboxRuntime.class);
        new SandboxRecoveryCleaner(runtime).cleanClosedAttempts();
        verify(runtime).cleanupManagedContainers();
    }

    @Test void failuresAreRedactedAndDoNotDisableLaterSweeps() {
        SandboxRuntime runtime = mock(SandboxRuntime.class);
        doThrow(new IllegalStateException("PRIVATE_FAILURE_SENTINEL")).when(runtime).cleanupManagedContainers();
        Logger logger = (Logger) LoggerFactory.getLogger(SandboxRecoveryCleaner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var cleaner = new SandboxRecoveryCleaner(runtime);
            assertThatCode(cleaner::cleanClosedAttempts).doesNotThrowAnyException();
            assertThatCode(cleaner::cleanClosedAttempts).doesNotThrowAnyException();
            assertThat(appender.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("PRIVATE_FAILURE_SENTINEL");
                assertThat(event.getKeyValuePairs().toString()).contains("SANDBOX_CLEANUP_FAILURE");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
