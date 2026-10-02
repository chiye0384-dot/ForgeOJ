package com.forgeoj.api.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.forgeoj.api.auth.ForgeOjPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class SubmissionNotificationHandlerTests {
    private final SubmissionMapper mapper = mock(SubmissionMapper.class);
    private final SubmissionNotificationHandler handler = new SubmissionNotificationHandler(mapper, new ObjectMapper(), 3, 1);

    @Test
    void duplicateAndRegressingDatabaseVersionsNeverProduceOlderFrames() throws Exception {
        when(mapper.findNoticeByOwner(1, "submission-1")).thenReturn(
                Optional.of(result(4)), Optional.of(result(4)), Optional.of(result(2)), Optional.of(result(5)));
        WebSocketSession socket = socket("one", 1, login(1));
        handler.afterConnectionEstablished(socket);
        handler.notifySubscribers(); handler.notifySubscribers(); handler.notifySubscribers();
        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, times(2)).sendMessage(messages.capture());
        assertThat(messages.getAllValues().stream().map(message -> message.getPayload().toString()))
                .containsExactly(
                        "{\"submissionId\":\"submission-1\",\"processingStatus\":\"RUNNING\",\"statusVersion\":4}",
                        "{\"submissionId\":\"submission-1\",\"processingStatus\":\"RUNNING\",\"statusVersion\":5}");
        handler.shutdown();
    }

    @Test
    void invalidatedSessionStopsReadsAndReleasesItsUserSlot() throws Exception {
        when(mapper.findNoticeByOwner(1, "submission-1")).thenReturn(Optional.of(result(1)));
        MockHttpSession login = login(1);
        WebSocketSession socket = socket("one", 1, login);
        handler.afterConnectionEstablished(socket);
        login.invalidate();
        handler.notifySubscribers(); handler.notifySubscribers();
        verify(mapper, times(1)).findNoticeByOwner(1, "submission-1");
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        WebSocketSession replacement = socket("new", 1, login(1));
        handler.afterConnectionEstablished(replacement);
        verify(replacement).sendMessage(any());
        handler.shutdown();
    }

    @Test
    void databaseFailureClosesWithoutPayloadOrRepeatedQueries() throws Exception {
        when(mapper.findNoticeByOwner(1, "submission-1"))
                .thenThrow(new IllegalStateException("private database diagnostic"));
        WebSocketSession socket = socket("one", 1, login(1));
        handler.afterConnectionEstablished(socket);
        handler.notifySubscribers();
        verify(socket, never()).sendMessage(any());
        verify(socket).close(CloseStatus.SERVER_ERROR);
        verify(mapper, times(1)).findNoticeByOwner(1, "submission-1");
    }

    @Test
    void sendFailureReleasesWatcherAndDoesNotRetryForever() throws Exception {
        when(mapper.findNoticeByOwner(1, "submission-1")).thenReturn(Optional.of(result(1)));
        WebSocketSession socket = socket("one", 1, login(1));
        doThrow(new IOException("disconnected")).when(socket).sendMessage(any());
        handler.afterConnectionEstablished(socket);
        handler.notifySubscribers();
        verify(socket, times(1)).sendMessage(any());
        verify(socket).close(CloseStatus.SERVER_ERROR);
        verify(mapper, times(1)).findNoticeByOwner(1, "submission-1");
    }

    @Test
    void perUserLimitDoesNotConsumeAnotherUsersSlotAndClosureAllowsReplacement() throws Exception {
        when(mapper.findNoticeByOwner(1, "submission-1")).thenReturn(Optional.of(result(1)));
        when(mapper.findNoticeByOwner(2, "submission-2"))
                .thenReturn(Optional.of(new SubmissionResult("submission-2", "RUNNING", 1)));
        WebSocketSession first = socket("one", 1, login(1));
        WebSocketSession excess = socket("excess", 1, login(1));
        WebSocketSession other = socket("other", 2, login(2));
        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(excess);
        handler.afterConnectionEstablished(other);
        verify(excess).close(new CloseStatus(1013, "Notification capacity reached"));
        verify(excess, never()).sendMessage(any());
        verify(other).sendMessage(any());
        handler.afterConnectionClosed(first, CloseStatus.NORMAL);
        WebSocketSession replacement = socket("new", 1, login(1));
        handler.afterConnectionEstablished(replacement);
        verify(replacement).sendMessage(any());
        handler.shutdown();
        verify(other).close(CloseStatus.GOING_AWAY);
        verify(replacement).close(CloseStatus.GOING_AWAY);
    }

    @Test
    void globalConnectionLimitAppliesAcrossDistinctOwners() throws Exception {
        SubmissionNotificationHandler limited = new SubmissionNotificationHandler(mapper, new ObjectMapper(), 2, 2);
        for (long userId = 1; userId <= 3; userId++) {
            when(mapper.findNoticeByOwner(userId, "submission-" + userId))
                    .thenReturn(Optional.of(new SubmissionResult("submission-" + userId, "RUNNING", 1)));
        }
        WebSocketSession first = socket("one", 1, login(1));
        WebSocketSession second = socket("two", 2, login(2));
        WebSocketSession excess = socket("three", 3, login(3));
        limited.afterConnectionEstablished(first);
        limited.afterConnectionEstablished(second);
        limited.afterConnectionEstablished(excess);
        verify(first).sendMessage(any());
        verify(second).sendMessage(any());
        verify(excess).close(new CloseStatus(1013, "Notification capacity reached"));
        verify(excess, never()).sendMessage(any());
        verify(mapper, never()).findNoticeByOwner(3, "submission-3");
        limited.shutdown();
    }

    private SubmissionResult result(long version) { return new SubmissionResult("submission-1", "RUNNING", version); }

    private MockHttpSession login(long userId) {
        MockHttpSession session = new MockHttpSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                new ForgeOjPrincipal(userId, "test-owner", "not-a-real-password-hash", true), null, List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private WebSocketSession socket(String socketId, long userId, MockHttpSession login) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(socketId);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenReturn(Map.of(SubmissionWatch.ATTRIBUTE,
                new SubmissionWatch(userId, "submission-" + userId, login)));
        return socket;
    }
}
