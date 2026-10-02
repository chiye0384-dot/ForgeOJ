package com.forgeoj.api.submission;

import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.ObjectMapper;

@Component
final class SubmissionNotificationHandler extends AbstractWebSocketHandler {
    private final SubmissionMapper mapper;
    private final ObjectMapper json;
    private final int maxConnections;
    private final int maxPerUser;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();

    SubmissionNotificationHandler(SubmissionMapper mapper, ObjectMapper json,
            @Value("${forgeoj.notifications.max-connections:20}") int maxConnections,
            @Value("${forgeoj.notifications.max-connections-per-user:2}") int maxPerUser) {
        if (maxConnections < 1 || maxPerUser < 1) throw new IllegalArgumentException("Invalid notification limits");
        this.mapper = mapper;
        this.json = json;
        this.maxConnections = maxConnections;
        this.maxPerUser = maxPerUser;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Object attribute = session.getAttributes().get(SubmissionWatch.ATTRIBUTE);
        if (!(attribute instanceof SubmissionWatch watch) || !watch.stillAuthenticated()) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.setTextMessageSizeLimit(512);
        session.setBinaryMessageSizeLimit(512);
        if (session instanceof StandardWebSocketSession standard) {
            // Verified against pinned Tomcat 11.0.24: bound synchronous send latency too.
            standard.getNativeSession().getUserProperties().put(
                    "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 1000L);
        }
        Connection connection = new Connection(watch,
                new ConcurrentWebSocketSessionDecorator(session, 1000, 8192));
        boolean admitted;
        synchronized (admissionLock) {
            long userConnections = connections.values().stream()
                    .filter(current -> current.watch.userId() == watch.userId()).count();
            admitted = connections.size() < maxConnections && userConnections < maxPerUser;
            if (admitted) connections.put(session.getId(), connection);
        }
        if (!admitted) session.close(new CloseStatus(1013, "Notification capacity reached"));
        else notifyCommittedSnapshot(session.getId(), connection);
    }

    @Scheduled(fixedDelayString = "${forgeoj.notifications.fixed-delay-ms:500}")
    void notifySubscribers() {
        connections.forEach(this::notifyCommittedSnapshot);
    }

    private void notifyCommittedSnapshot(String id, Connection connection) {
        synchronized (connection) {
            if (connections.get(id) != connection) return;
            try {
                if (!connection.session.isOpen() || !connection.watch.stillAuthenticated()) {
                    close(id, connection, CloseStatus.POLICY_VIOLATION);
                    return;
                }
                var result = mapper.findNoticeByOwner(connection.watch.userId(), connection.watch.submissionId());
                // Check session again after the DB read; never send after known logout/expiry.
                if (result.isEmpty() || !connection.watch.stillAuthenticated()) {
                    close(id, connection, CloseStatus.POLICY_VIOLATION);
                    return;
                }
                SubmissionResult notice = result.get();
                if (notice.statusVersion() <= connection.lastVersion) return;
                connection.session.sendMessage(new TextMessage(json.writeValueAsString(notice)));
                connection.lastVersion = notice.statusVersion();
                if (terminal(notice.processingStatus())) close(id, connection, CloseStatus.NORMAL);
            } catch (Exception failure) {
                // Push is disposable. A DB/transport failure releases the watcher; GET remains authoritative.
                close(id, connection, CloseStatus.SERVER_ERROR);
            }
        }
    }

    private boolean terminal(String status) {
        return "FINISHED".equals(status) || "CANCELLED".equals(status) || "SYSTEM_ERROR".equals(status);
    }

    private void close(String id, Connection connection, CloseStatus status) {
        synchronized (connection) {
            connections.remove(id, connection);
            try { connection.session.close(status); } catch (Exception ignored) { /* Already released. */ }
        }
    }

    @PreDestroy
    void shutdown() {
        connections.forEach((id, connection) -> close(id, connection, CloseStatus.GOING_AWAY));
    }

    @org.springframework.context.event.EventListener
    void sessionsRevoked(com.forgeoj.api.auth.AccountSessionsRevoked event) {
        connections.forEach((id, connection) -> {
            if (connection.watch.userId() == event.userId()
                    && (event.sessionId() == null || event.sessionId().equals(connection.watch.sessionId())))
                close(id, connection, CloseStatus.POLICY_VIOLATION);
        });
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        Connection connection = connections.get(session.getId());
        if (connection != null) close(session.getId(), connection, CloseStatus.POLICY_VIOLATION);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Connection connection = connections.get(session.getId());
        if (connection != null) close(session.getId(), connection, CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connections.remove(session.getId());
    }

    private static final class Connection {
        final SubmissionWatch watch;
        final WebSocketSession session;
        long lastVersion = -1;
        Connection(SubmissionWatch watch, WebSocketSession session) {
            this.watch = watch;
            this.session = session;
        }
    }
}
