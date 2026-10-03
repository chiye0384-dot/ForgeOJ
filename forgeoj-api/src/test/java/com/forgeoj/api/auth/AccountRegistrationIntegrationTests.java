package com.forgeoj.api.auth;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.*;

@Testcontainers
@SpringBootTest(properties={"spring.rabbitmq.listener.simple.auto-startup=false","forgeoj.auth.mail.mode=disabled"})
class AccountRegistrationIntegrationTests {
    @Container static final MySQLContainer MYSQL = new com.forgeoj.api.testinfra.DirectMySQLContainer(DockerImageName.parse(
            "container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret")
            .withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl); r.add("spring.datasource.username",()->"forgeoj_api"); r.add("spring.datasource.password",()->"m0-api-test-secret");
        r.add("spring.flyway.url",MYSQL::getJdbcUrl); r.add("spring.flyway.user",()->"forgeoj_migrator"); r.add("spring.flyway.password",()->"m0-migrator-test-secret");
    }
    @Autowired AccountService service;
    JdbcTemplate admin(){ return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret")); }
    @Test void registrationCreatesPendingAccountQuotaAndOnlyTokenDigestTogether() {
        service.register("NewLearner","First@Example.test","register-fixture-password","同名昵称");
        var a=admin();
        var account=a.queryForMap("SELECT id,email,status,password_hash FROM user_account WHERE username='NewLearner'");
        assertThat(account.get("email")).isEqualTo("first@example.test");
        assertThat(account.get("status")).isEqualTo("PENDING_VERIFICATION");
        assertThat(account.get("password_hash").toString()).startsWith("$2").doesNotContain("fixture");
        assertThat(a.queryForObject("SELECT COUNT(*) FROM user_judge_quota_lock WHERE user_id=?",Integer.class,account.get("id"))).isEqualTo(1);
        assertThat(a.queryForObject("SELECT token_sha256 FROM account_action_token WHERE user_id=?",String.class,account.get("id"))).matches("[a-f0-9]{64}");
    }
    @Test void quotaInsertFailureRollsBackAccountAndToken() {
        var a=admin(); a.execute("REVOKE INSERT ON forgeoj.user_judge_quota_lock FROM 'forgeoj_api'@'%'");
        try { assertThatThrownBy(()->service.register("RollbackUser","rollback@example.test","register-fixture-password",null)).isInstanceOf(RuntimeException.class);
            assertThat(a.queryForObject("SELECT COUNT(*) FROM user_account WHERE username='RollbackUser'",Integer.class)).isZero();
            assertThat(a.queryForObject("SELECT COUNT(*) FROM account_action_token WHERE target_email='rollback@example.test'",Integer.class)).isZero();
        } finally { a.execute("GRANT INSERT ON forgeoj.user_judge_quota_lock TO 'forgeoj_api'@'%'"); }
    }
    @Test void concurrentCaseEquivalentRegistrationsHaveOneOwnerWithoutChangingPassword() throws Exception {
        try(var pool=Executors.newFixedThreadPool(6)) {
            var futures=IntStream.range(0,6).mapToObj(i->pool.submit(()->{service.register(i%2==0?"ConcurrentUser":"concurrentuser","Concurrent@Example.test","register-fixture-password",null); return true;})).toList();
            for(var future:futures) assertThat(future.get(20,TimeUnit.SECONDS)).isTrue();
        }
        var a=admin(); assertThat(a.queryForObject("SELECT COUNT(*) FROM user_account WHERE username='concurrentuser'",Integer.class)).isEqualTo(1);
        assertThat(a.queryForObject("SELECT COUNT(*) FROM user_judge_quota_lock q JOIN user_account u ON u.id=q.user_id WHERE u.username='concurrentuser'",Integer.class)).isEqualTo(1);
        assertThat(a.queryForObject("SELECT COUNT(*) FROM account_action_token WHERE target_email='concurrent@example.test'",Integer.class)).isEqualTo(1);
    }
}
