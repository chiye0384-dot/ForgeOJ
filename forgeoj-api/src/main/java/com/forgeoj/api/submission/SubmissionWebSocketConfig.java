package com.forgeoj.api.submission;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
class SubmissionWebSocketConfig implements WebSocketConfigurer {
    private final SubmissionNotificationHandler handler;
    private final SubmissionHandshakeInterceptor interceptor;

    SubmissionWebSocketConfig(SubmissionNotificationHandler handler, SubmissionHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/submissions/*/events").addInterceptors(interceptor);
    }
}
