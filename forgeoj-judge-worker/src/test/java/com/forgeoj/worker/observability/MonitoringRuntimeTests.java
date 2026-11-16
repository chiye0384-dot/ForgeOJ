/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MonitoringRuntimeTests {
  private static final String TOKEN = "public-test-only-monitoring-token-32bytes";

  @Test
  void workerMonitoringIsAbsentByDefault() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.register(MonitoringRuntime.class);
      context.refresh();
      assertTrue(context.getBeansOfType(MonitoringRuntime.class).isEmpty());
    }
  }

  @Test
  void readinessRequiresApplicationDatabaseSchemaAndEveryConsumerAndNeverFollowsRedisOrSearch()
      throws Exception {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:worker-monitor-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var db = new JdbcTemplate(source);
    db.execute("CREATE TABLE judge_task(id INT)");
    var registry = mock(RabbitListenerEndpointRegistry.class);
    var consumer = mock(SimpleMessageListenerContainer.class);
    when(registry.getListenerContainers()).thenReturn(List.of(consumer));
    when(consumer.isRunning()).thenReturn(true);
    when(consumer.getActiveConsumerCount()).thenReturn(0);
    var availability = mock(ApplicationAvailability.class);
    when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
    var runtime =
        new MonitoringRuntime(source, registry, availability, "127.0.0.1", 0, TOKEN, false);
    try (var ownedRuntime = runtimeHolder(runtime);
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      runtime.start();
      runtime.refresh();
      assertEquals(503, status(client, runtime, "/health/ready"));
      assertEquals(200, status(client, runtime, "/health/live"));
      when(consumer.getActiveConsumerCount()).thenReturn(1);
      runtime.refresh();
      assertEquals(200, status(client, runtime, "/health/ready"));
      when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
      assertEquals(503, status(client, runtime, "/health/ready"));
      when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
      db.execute("DROP TABLE judge_task");
      runtime.refresh();
      assertEquals(503, status(client, runtime, "/health/ready"));
      var metrics = client.send(request(runtime, "/metrics"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, metrics.statusCode());
      assertTrue(metrics.body().contains("collection_success 0"));
      assertFalse(metrics.body().contains("jdbc:h2"));
      assertFalse(metrics.body().contains(TOKEN));
    } finally {
      runtime.stop();
      db.execute("SHUTDOWN");
    }
  }

  private static AutoCloseable runtimeHolder(MonitoringRuntime runtime) {
    return runtime::stop;
  }

  private static HttpRequest request(MonitoringRuntime runtime, String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + runtime.port() + path))
        .header("Authorization", "Bearer " + TOKEN)
        .timeout(Duration.ofSeconds(3))
        .build();
  }

  private static int status(HttpClient client, MonitoringRuntime runtime, String path)
      throws Exception {
    return client.send(request(runtime, path), HttpResponse.BodyHandlers.discarding()).statusCode();
  }
}
