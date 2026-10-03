package com.forgeoj.api.auth;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.*;

@Testcontainers @ActiveProfiles("test")
@SpringBootTest(properties={"spring.rabbitmq.listener.simple.auto-startup=false","forgeoj.auth.mail.mode=local","forgeoj.auth.mail.port=0"})
class AccountFlowIntegrationTests {
    @Container static final MySQLContainer MYSQL = new com.forgeoj.api.testinfra.DirectMySQLContainer(DockerImageName.parse(
            "container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret")
            .withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl); r.add("spring.datasource.username",()->"forgeoj_api"); r.add("spring.datasource.password",()->"m0-api-test-secret");
        r.add("spring.flyway.url",MYSQL::getJdbcUrl); r.add("spring.flyway.user",()->"forgeoj_migrator"); r.add("spring.flyway.password",()->"m0-migrator-test-secret");
    }
    @Autowired AccountService service;
    @Autowired AccountJwt jwt;
    @Autowired LocalAccountMail mail;
    @Autowired AccountMapper mapper;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ApplicationEventPublisher events;
    @Autowired DataSource dataSource;
    JdbcTemplate db(){return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret"));}
    String token(String address,String purpose){String link=mail.messages().stream().filter(m->m.recipient().equals(address)&&m.purpose().equals(purpose)).findFirst().orElseThrow().link();return URI.create(link).getFragment().split("&token=")[1];}
    AccountService.Login active(String name) {
        String email=name.toLowerCase()+"@example.test";
        service.register(name,email,"account-fixture-password",null);
        service.confirm(token(email,"ACTIVATE"),"ACTIVATE",null);
        return service.login(name,"account-fixture-password");
    }
    @Test void pendingCannotLoginAndSingleUseActivationEnablesEmailLogin() {
        service.register("PendingUser","pending@example.test","account-fixture-password",null);
        assertThatThrownBy(()->service.login("PendingUser","account-fixture-password")).hasMessageContaining("401");
        String token=token("pending@example.test","ACTIVATE");
        service.confirm(token,"ACTIVATE",null);
        assertThatThrownBy(()->service.confirm(token,"ACTIVATE",null)).hasMessageContaining("400");
        assertThat(service.login("PENDING@EXAMPLE.TEST","account-fixture-password").userId()).isPositive();
    }
    @Test void expiredOrWrongPurposeTokenDoesNotActivateOrChangePassword() {
        service.register("ExpiredUser","expired@example.test","account-fixture-password",null);
        String token=token("expired@example.test","ACTIVATE");
        assertThatThrownBy(()->service.confirm(token,"RESET_PASSWORD","new-account-password")).hasMessageContaining("400");
        db().update("UPDATE account_action_token SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE target_email='expired@example.test'");
        assertThatThrownBy(()->service.confirm(token,"ACTIVATE",null)).hasMessageContaining("400");
    }
    @Test void consumedRefreshRevokesOnlyItsOwnSessionAndRejectsUnexpiredJwt() {
        var one=active("RefreshUser");var two=service.login("RefreshUser","account-fixture-password");
        String access=jwt.issue(one);assertThat(jwt.verify(access).getSubject()).isEqualTo(Long.toString(one.userId()));
        var rotated=service.refresh(one.refresh());assertThat(rotated.refresh()).isNotEqualTo(one.refresh());
        assertThatThrownBy(()->service.refresh(one.refresh())).hasMessageContaining("401");
        assertThat(service.authenticated(one.userId(),one.sessionId())).isFalse();
        assertThat(service.authenticated(two.userId(),two.sessionId())).isTrue();
        assertThat(jwt.verify(access).getExpiresAt()).isAfter(Instant.now());
        assertThatThrownBy(()->service.refresh(rotated.refresh())).hasMessageContaining("401");
    }
    @Test void concurrentRefreshHasOneWinnerAndReuseRevokesWinner() throws Exception {
        var login=active("RaceRefresh");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Boolean> task=()->{start.await();try{service.refresh(login.refresh());return true;}catch(org.springframework.web.server.ResponseStatusException rejected){return false;}};
            var one=pool.submit(task);var two=pool.submit(task);start.countDown();assertThat((one.get()?1:0)+(two.get()?1:0)).isEqualTo(1);
        }
        assertThat(service.authenticated(login.userId(),login.sessionId())).isFalse();
    }
    @Test void logoutThenLogoutAllHaveIndependentAndGlobalEffects() {
        var one=active("LogoutUser");var two=service.login("LogoutUser","account-fixture-password");
        service.logout(one.refresh());service.logout(one.refresh());
        assertThat(service.authenticated(one.userId(),one.sessionId())).isFalse();assertThat(service.authenticated(two.userId(),two.sessionId())).isTrue();
        service.logoutAll(two.userId(),two.sessionId());assertThat(service.authenticated(two.userId(),two.sessionId())).isFalse();
    }
    @Test void passwordResetConsumesTokenRevokesAllSessionsAndOldPassword() {
        var one=active("ResetUser");var two=service.login("ResetUser","account-fixture-password");
        service.request("resetuser@example.test","RESET_PASSWORD");String token=token("resetuser@example.test","RESET_PASSWORD");
        service.confirm(token,"RESET_PASSWORD","changed-account-password");
        assertThat(service.authenticated(one.userId(),one.sessionId())).isFalse();assertThat(service.authenticated(two.userId(),two.sessionId())).isFalse();
        assertThatThrownBy(()->service.refresh(one.refresh())).hasMessageContaining("401");
        assertThatThrownBy(()->service.confirm(token,"RESET_PASSWORD","other-account-password")).hasMessageContaining("400");
        assertThatThrownBy(()->service.login("ResetUser","account-fixture-password")).hasMessageContaining("401");
        assertThat(service.login("ResetUser","changed-account-password")).isNotNull();
    }
    @Test void changePasswordInvalidatesPendingResetAndAllOldSessions() {
        var login=active("ChangeUser");service.request("changeuser@example.test","RESET_PASSWORD");String token=token("changeuser@example.test","RESET_PASSWORD");
        service.changePassword(login.userId(),login.sessionId(),"account-fixture-password","changed-account-password");
        assertThatThrownBy(()->service.confirm(token,"RESET_PASSWORD","another-password")).hasMessageContaining("400");
        assertThat(service.authenticated(login.userId(),login.sessionId())).isFalse();
    }
    @Test void disabledUserCannotUseJwtRefreshResetOrActivation() {
        var login=active("DisabledUser");service.request("disableduser@example.test","RESET_PASSWORD");String token=token("disableduser@example.test","RESET_PASSWORD");
        service.disable(login.userId());assertThat(service.authenticated(login.userId(),login.sessionId())).isFalse();
        assertThatThrownBy(()->service.refresh(login.refresh())).hasMessageContaining("401");assertThatThrownBy(()->service.login("DisabledUser","account-fixture-password")).hasMessageContaining("401");
        assertThatThrownBy(()->service.confirm(token,"RESET_PASSWORD","another-password")).hasMessageContaining("400");
    }
    @Test void verifiedBindingUpdatesOnlyAfterEmailProofAndInvalidatesOldReset() {
        var login=active("BindUser");service.request("binduser@example.test","RESET_PASSWORD");String reset=token("binduser@example.test","RESET_PASSWORD");
        service.requestBinding(login.userId(),login.sessionId(),"account-fixture-password","bound@example.test");
        assertThat(db().queryForObject("SELECT email FROM user_account WHERE id=?",String.class,login.userId())).isEqualTo("binduser@example.test");
        service.confirm(token("bound@example.test","BIND_EMAIL"),"BIND_EMAIL",null);
        assertThatThrownBy(()->service.confirm(reset,"RESET_PASSWORD","another-password")).hasMessageContaining("400");
        assertThat(service.login("bound@example.test","account-fixture-password")).isNotNull();
    }
    @Test void repeatedMailRequestsAreBoundedWithoutReplacingTheFirstToken() {
        service.register("MailLimit","maillimit@example.test","account-fixture-password",null);
        String token=token("maillimit@example.test","ACTIVATE");for(int i=0;i<8;i++)service.request("maillimit@example.test","ACTIVATE");
        assertThat(mail.messages().stream().filter(m->m.recipient().equals("maillimit@example.test")).count()).isEqualTo(1);
        service.confirm(token,"ACTIVATE",null);
        service.request("not-present@example.test","RESET_PASSWORD");
    }

    @Test void outerRollbackUndoesRegistrationAndNeverDeliversMail() {
        String username = uniqueUsername("OuterRollback");
        String email = username.toLowerCase(Locale.ROOT) + "@example.test";
        long[] registeredId = new long[1];

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.register(username, email, "account-fixture-password", null);
            registeredId[0] = assertPendingRegistration(new JdbcTemplate(dataSource), username, email);
            assertThat(mail.messages()).noneMatch(message -> message.recipient().equals(email));
            status.setRollbackOnly();
        });

        JdbcTemplate database = db();
        assertThat(database.queryForObject(
                "SELECT COUNT(*) FROM user_account WHERE username=?", Integer.class, username))
                .isZero();
        assertThat(database.queryForObject(
                "SELECT COUNT(*) FROM user_judge_quota_lock WHERE user_id=?", Integer.class,
                registeredId[0])).isZero();
        assertThat(database.queryForObject(
                "SELECT COUNT(*) FROM account_action_token WHERE user_id=?", Integer.class,
                registeredId[0])).isZero();
        assertThat(mail.messages()).noneMatch(message -> message.recipient().equals(email));
    }

    @Test void outerCommitDeliversExactlyOnceAfterRegistrationBecomesDurable() {
        String username = uniqueUsername("OuterCommit");
        String email = username.toLowerCase(Locale.ROOT) + "@example.test";

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.register(username, email, "account-fixture-password", null);
            assertPendingRegistration(new JdbcTemplate(dataSource), username, email);
            assertThat(mail.messages()).noneMatch(message -> message.recipient().equals(email));
        });

        assertPendingRegistration(db(), username, email);
        assertThat(mail.messages().stream()
                .filter(message -> message.recipient().equals(email)
                        && message.purpose().equals("ACTIVATE")))
                .hasSize(1);
        assertThat(db().queryForObject(
                "SELECT COUNT(*) FROM account_action_token WHERE target_email=? AND token_sha256=?",
                Integer.class, email, AccountSecrets.digest(token(email, "ACTIVATE"))))
                .isEqualTo(1);
    }

    @Test void deliveryFailurePreservesPendingAccountQuotaAndActivationDigest() {
        String username = uniqueUsername("FailedMail");
        String email = username.toLowerCase(Locale.ROOT) + "@example.test";
        AtomicInteger attempts = new AtomicInteger();
        AccountService failingDeliveryService = new AccountService(
                mapper, passwords,
                (recipient, purpose, token) -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("Simulated mail delivery unavailable");
                },
                transactionManager, events);

        assertThatCode(() -> failingDeliveryService.register(
                username, email, "account-fixture-password", null))
                .doesNotThrowAnyException();

        assertThat(attempts).hasValue(1);
        assertPendingRegistration(db(), username, email);
        assertThat(mail.messages()).noneMatch(message -> message.recipient().equals(email));
        assertThatThrownBy(() -> service.login(username, "account-fixture-password"))
                .hasMessageContaining("401");
    }

    private long assertPendingRegistration(JdbcTemplate database, String username, String email) {
        long id = database.queryForObject(
                "SELECT id FROM user_account WHERE username=? AND email=?", Long.class, username, email);
        assertThat(database.queryForObject(
                "SELECT status FROM user_account WHERE id=?", String.class, id))
                .isEqualTo("PENDING_VERIFICATION");
        assertThat(database.queryForObject(
                "SELECT COUNT(*) FROM user_judge_quota_lock WHERE user_id=?", Integer.class, id))
                .isEqualTo(1);
        assertThat(database.queryForObject(
                "SELECT COUNT(*) FROM account_action_token WHERE user_id=? AND purpose='ACTIVATE'"
                        + " AND target_email=? AND consumed_at IS NULL"
                        + " AND expires_at>CURRENT_TIMESTAMP(6) AND CHAR_LENGTH(token_sha256)=64",
                Integer.class, id, email)).isEqualTo(1);
        return id;
    }

    private String uniqueUsername(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
