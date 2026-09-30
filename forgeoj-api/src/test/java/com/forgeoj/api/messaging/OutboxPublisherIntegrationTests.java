package com.forgeoj.api.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeoj.api.submission.SubmissionResult;
import com.forgeoj.api.submission.SubmissionService;
import com.jayway.jsonpath.JsonPath;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Testcontainers
@SpringBootTest(
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/devdata",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "spring.rabbitmq.listener.direct.auto-startup=false",
            "forgeoj.outbox.publisher.enabled=false",
            "forgeoj.outbox.publisher.confirm-timeout-ms=5000"
        })
class OutboxPublisherIntegrationTests {

    private static final String MYSQL_IMAGE =
            "container-registry.oracle.com/mysql/community-server:8.4.12"
                    + "@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be";
    private static final String RABBIT_IMAGE =
            "rabbitmq:4.3.6-management"
                    + "@sha256:cdf40d8cb363d145e377ed88d59696a42386ffe54b30125f10eb128b862eea95";
    private static final String API_PASSWORD = "m0-api-test-secret";
    private static final String MIGRATOR_PASSWORD = "m0-migrator-test-secret";
    private static final String RABBIT_USER = "forgeoj";
    private static final String RABBIT_PASSWORD = "m0-rabbit-test-secret";
    private static final String SOURCE =
            """
            public class Main {
                public static void main(String[] args) {
                    System.out.println("OUTBOX_SOURCE_SENTINEL");
                }
            }
            """;

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(
                            DockerImageName.parse(MYSQL_IMAGE)
                                    .asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("forgeoj")
                    .withUsername("bootstrap")
                    .withPassword("bootstrap-test-secret")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("mysql/init-test-users.sql"),
                            "/docker-entrypoint-initdb.d/01-init-test-users.sql");

    @Container
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(
                            DockerImageName.parse(RABBIT_IMAGE)
                                    .asCompatibleSubstituteFor("rabbitmq"))
                    .withAdminUser(RABBIT_USER)
                    .withAdminPassword(RABBIT_PASSWORD)
                    .withEnv("RABBITMQ_DEFAULT_VHOST", "/forgeoj");

    @DynamicPropertySource
    static void runtimeProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "forgeoj_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "forgeoj_migrator");
        registry.add("spring.flyway.password", () -> MIGRATOR_PASSWORD);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> RABBIT_USER);
        registry.add("spring.rabbitmq.password", () -> RABBIT_PASSWORD);
        registry.add("spring.rabbitmq.virtual-host", () -> "/forgeoj");
    }

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private DataSource dataSource;

    @Test
    void marksPublishedOnlyAfterConfirmedAndRoutableDelivery() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        SubmissionResult delivered = createSubmission();

        assertThat(publishedAt(jdbc, delivered.submissionId())).isNull();
        assertThat(outboxPublisher.publishPending()).isEqualTo(1);
        assertThat(publishedAt(jdbc, delivered.submissionId())).isNotNull();

        Message message = rabbitTemplate.receive(RabbitTopology.QUEUE, 5000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getReceivedDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(payload, "$.submissionId"))
                .isEqualTo(delivered.submissionId());
        assertThat((String) JsonPath.read(payload, "$.taskType"))
                .isEqualTo("JUDGE_SUBMISSION");
        assertThat((Integer) JsonPath.read(payload, "$.contractVersion")).isEqualTo(1);
        assertThat(((java.util.Map<?, ?>) JsonPath.parse(payload).read("$"))).hasSize(4);
        assertThat(payload)
                .doesNotContain("OUTBOX_SOURCE_SENTINEL")
                .doesNotContain("37881a92ca996970e09475fdb29435b9bc13ae1501fa118e5fd9afd47e561adf");

        String taskId = taskId(jdbc, delivered.submissionId());
        insertOutbox(jdbc, taskId, delivered.submissionId(), "JUDGE_TASK_QUEUED", 1, 86400);
        insertOutbox(
                jdbc,
                taskId,
                delivered.submissionId(),
                "JUDGE_TASK_DEAD_LETTERED",
                1,
                0);

        assertThat(outboxPublisher.publishPending()).isEqualTo(1);
        assertThat(rabbitTemplate.receive(RabbitTopology.QUEUE, 200)).isNull();
        Message deadLetter =
                rabbitTemplate.receive(RabbitTopology.DEAD_LETTER_QUEUE, 5000);
        assertThat(deadLetter).isNotNull();
        assertThat(new String(deadLetter.getBody(), StandardCharsets.UTF_8))
                .contains(delivered.submissionId());

        SubmissionResult unroutable = createSubmission();
        assertThat(rabbitAdmin.deleteQueue(RabbitTopology.QUEUE)).isTrue();

        assertThat(outboxPublisher.publishPending()).isZero();
        assertThat(publishedAt(jdbc, unroutable.submissionId())).isNull();
    }

    private SubmissionResult createSubmission() {
        return submissionService.create(
                1L,
                "sum-two-integers",
                UUID.randomUUID().toString(),
                "JAVA_21",
                SOURCE);
    }

    private Object publishedAt(JdbcTemplate jdbc, String submissionId) {
        return jdbc.queryForObject(
                """
                SELECT o.published_at
                FROM outbox_event o
                JOIN judge_task jt ON jt.id = o.aggregate_id
                WHERE jt.submission_id = ?
                """,
                Object.class,
                submissionId);
    }

    private String taskId(JdbcTemplate jdbc, String submissionId) {
        return jdbc.queryForObject(
                "SELECT id FROM judge_task WHERE submission_id = ?",
                String.class,
                submissionId);
    }

    private void insertOutbox(
            JdbcTemplate jdbc,
            String taskId,
            String submissionId,
            String eventType,
            int sequenceNo,
            long delaySeconds) {
        jdbc.update(
                """
                INSERT INTO outbox_event (
                    id, aggregate_type, aggregate_id, event_type, contract_version,
                    sequence_no, payload, next_attempt_at
                ) VALUES (?, 'JUDGE_TASK', ?, ?, 1, ?, JSON_OBJECT(
                    'taskId', ?,
                    'submissionId', ?,
                    'taskType', 'JUDGE_SUBMISSION',
                    'contractVersion', 1
                ), TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)))
                """,
                UUID.randomUUID().toString(),
                taskId,
                eventType,
                sequenceNo,
                taskId,
                submissionId,
                delaySeconds);
    }
}
