/* Copyright 2026 池也. SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Exercises the real security chain; no mocked authentication or CSRF postprocessors. */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "spring.rabbitmq.listener.direct.auto-startup=false",
            "forgeoj.auth.mail.mode=local",
            "forgeoj.auth.mail.port=0"
        })
class AccountHttpIntegrationTests {
    private static final String PASSWORD = "account-http-fixture-password";
    private static final String NEXT_PASSWORD = "changed-account-http-password";
    private static final String ORIGIN = "http://localhost";
    private static final String PROTECTED_QUERY =
            "/api/v1/submissions/00000000-0000-0000-0000-000000000001";

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(
                            DockerImageName.parse(
                                            "container-registry.oracle.com/mysql/community-server:8.4.12"
                                                    + "@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be")
                                    .asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("forgeoj")
                    .withUsername("bootstrap")
                    .withPassword("bootstrap-test-secret")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("mysql/init-test-users.sql"),
                            "/docker-entrypoint-initdb.d/01-users.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "forgeoj_api");
        registry.add("spring.datasource.password", () -> "m0-api-test-secret");
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "forgeoj_migrator");
        registry.add("spring.flyway.password", () -> "m0-migrator-test-secret");
    }

    @Autowired private MockMvc mvc;
    @Autowired private AccountService accounts;
    @Autowired private AccountMapper mapper;
    @Autowired private AccountJwt jwt;
    @Autowired private LocalAccountMail mail;

    @Test
    void currentLogoutRejectsSavedAccessJwtAndLeavesSecondSessionValid() throws Exception {
        Browser one = active("HttpLogout");
        Browser two = login("HttpLogout", PASSWORD);
        assertAuthenticated(one);
        write(one, "logout", "{}").andExpect(status().isNoContent());
        assertRejected(one);
        assertAuthenticated(two);
    }

    @Test
    void currentLogoutWithAccessAndCsrfButWithoutRefreshStillRevokesItsSession()
            throws Exception {
        Browser original = active("HttpAccessLogout");
        Browser two = login("HttpAccessLogout", PASSWORD);
        Browser accessOnly = original.without(AccountCookies.REFRESH);
        assertThat(accessOnly.cookies()).extracting(Cookie::getName)
                .contains(AccountCookies.ACCESS, AccountCookies.CSRF)
                .doesNotContain(AccountCookies.REFRESH);
        write(accessOnly, "logout", "{}").andExpect(status().isNoContent());
        // Reuse the original cookies rather than the logout response's cleared cookies.
        assertRejected(original);
        assertAuthenticated(two);
    }

    @Test
    void refreshOnlyLogoutRevokesSavedAccessJwtWhenAccessCookieIsAbsent() throws Exception {
        Browser original = active("HttpRefreshLogout");
        write(original.without(AccountCookies.ACCESS), "logout", "{}")
                .andExpect(status().isNoContent());
        assertRejected(original);
    }

    @Test
    void logoutAllRejectsBothUnexpiredAccessJwts() throws Exception {
        Browser one = active("HttpLogoutAll");
        Browser two = login("HttpLogoutAll", PASSWORD);
        write(one, "logout-all", "{}").andExpect(status().isNoContent());
        assertRejected(one);
        assertRejected(two);
        login("HttpLogoutAll", PASSWORD);
    }

    @Test
    void reusedRefreshRevokesOriginalAndRotatedAccessJwtsWithoutRevokingAnotherSession()
            throws Exception {
        Browser one = active("HttpRefreshReuse");
        Browser two = login("HttpRefreshReuse", PASSWORD);
        MvcResult refreshed = write(one, "refresh", "{}")
                .andExpect(status().isOk()).andReturn();
        Browser rotated = new Browser(
                new Cookie[] {
                    refreshed.getResponse().getCookie(AccountCookies.ACCESS),
                    refreshed.getResponse().getCookie(AccountCookies.REFRESH),
                    one.cookie(AccountCookies.CSRF)
                }, one.csrfHeader(), one.csrfToken());
        assertAuthenticated(rotated);
        // Deliberately replay the pre-rotation refresh cookie through the real controller.
        write(one, "refresh", "{}").andExpect(status().isUnauthorized());
        assertRejected(one);
        assertRejected(rotated);
        assertAuthenticated(two);
        write(rotated, "refresh", "{}").andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChangeRejectsEveryOldJwtAndOldPassword() throws Exception {
        Browser one = active("HttpChange");
        Browser two = login("HttpChange", PASSWORD);
        write(
                        one,
                        "password/change",
                        "{\"currentPassword\":\"" + PASSWORD + "\",\"password\":\""
                                + NEXT_PASSWORD + "\"}")
                .andExpect(status().isNoContent());
        assertRejected(one);
        assertRejected(two);
        loginRequest(anonymous(), "HttpChange", PASSWORD).andExpect(status().isUnauthorized());
        assertAuthenticated(login("HttpChange", NEXT_PASSWORD));
    }

    @Test
    void passwordResetRejectsEveryOldJwtAndCannotBeConsumedTwice() throws Exception {
        Browser one = active("HttpReset");
        Browser two = login("HttpReset", PASSWORD);
        Browser anonymous = anonymous();
        write(anonymous, "password-reset/request", "{\"email\":\"httpreset@example.test\"}")
                .andExpect(status().isAccepted());
        String resetToken = token("httpreset@example.test", "RESET_PASSWORD");
        String body = "{\"token\":\"" + resetToken + "\",\"password\":\""
                + NEXT_PASSWORD + "\"}";
        write(anonymous, "password-reset/confirm", body).andExpect(status().isNoContent());
        assertRejected(one);
        assertRejected(two);
        write(anonymous(), "password-reset/confirm", body).andExpect(status().isBadRequest());
        loginRequest(anonymous(), "HttpReset", PASSWORD).andExpect(status().isUnauthorized());
        assertAuthenticated(login("HttpReset", NEXT_PASSWORD));
    }

    @Test
    void disablingAccountRejectsEveryOldJwtAndRefreshCookie() throws Exception {
        Browser one = active("HttpDisabled");
        Browser two = login("HttpDisabled", PASSWORD);
        long id = Long.parseLong(jwt.verify(one.cookie(AccountCookies.ACCESS).getValue()).getSubject());
        // There is intentionally no ordinary-user endpoint to disable accounts.
        accounts.disable(id);
        assertRejected(one);
        assertRejected(two);
        write(one, "refresh", "{}").andExpect(status().isUnauthorized());
        loginRequest(anonymous(), "HttpDisabled", PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void bindingTokenCannotBeConfirmedByAnotherAccountAndFailureDoesNotConsumeIt()
            throws Exception {
        Browser owner = active("HttpBindOwner");
        Browser other = active("HttpBindOther");
        String target = "http-bound-owner@example.test";
        requestBinding(owner, target);
        String token = token(target, "BIND_EMAIL");
        assertThat(mapper.findAccount("HttpBindOwner").orElseThrow().email())
                .isEqualTo("httpbindowner@example.test");
        write(other, "email-binding/confirm", tokenBody(token)).andExpect(status().isBadRequest());
        assertThat(mapper.findAccount("HttpBindOwner").orElseThrow().email())
                .isEqualTo("httpbindowner@example.test");
        write(owner, "email-binding/confirm", tokenBody(token)).andExpect(status().isNoContent());
        assertThat(mapper.findAccount("HttpBindOwner").orElseThrow().email()).isEqualTo(target);
        assertAuthenticated(login(target, PASSWORD));
    }

    @Test
    void revokedBindingSessionCannotConsumeTokenButAnotherOwnerSessionCan() throws Exception {
        Browser owner = active("HttpBindRevoked");
        Browser liveOwner = login("HttpBindRevoked", PASSWORD);
        String target = "http-bound-live@example.test";
        requestBinding(owner, target);
        String token = token(target, "BIND_EMAIL");
        write(owner, "logout", "{}").andExpect(status().isNoContent());
        write(owner, "email-binding/confirm", tokenBody(token)).andExpect(status().isUnauthorized());
        assertThat(mapper.findAccount("HttpBindRevoked").orElseThrow().email())
                .isEqualTo("httpbindrevoked@example.test");
        write(liveOwner, "email-binding/confirm", tokenBody(token))
                .andExpect(status().isNoContent());
        assertThat(mapper.findAccount("HttpBindRevoked").orElseThrow().email()).isEqualTo(target);
    }

    @Test
    void authenticatedReadsKeepCsrfAndWritesRequireBothExactOriginAndCsrf() throws Exception {
        Browser owner = active("HttpCsrf");
        for (int attempt = 0; attempt < 2; attempt++) {
            MvcResult result = mvc.perform(get("/api/v1/auth/session").cookie(owner.cookies()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(true))
                    .andExpect(jsonPath("$.csrf.token").value(owner.csrfToken()))
                    .andReturn();
            assertThat(result.getRequest().getSession(false)).isNull();
            assertThat(result.getResponse().getHeaders("Set-Cookie"))
                    .noneMatch(header -> header.startsWith(AccountCookies.CSRF + "="));
        }

        mvc.perform(post("/api/v1/auth/logout-all")
                        .cookie(owner.cookies()).header("Origin", "http://evil.test")
                        .header(owner.csrfHeader(), owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout-all")
                        .cookie(owner.cookies()).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout-all")
                        .cookie(owner.cookies()).header(owner.csrfHeader(), owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertAuthenticated(owner);
        write(owner, "logout-all", "{}").andExpect(status().isNoContent());
        assertRejected(owner);
    }

    @Test
    void unavailableSessionDatabaseFailsClosedForAccessAndRefreshWithoutConsumingSession()
            throws Exception {
        Browser owner = active("HttpUnavailable");
        assertAuthenticated(owner);
        var migrator = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), "forgeoj_migrator", "m0-migrator-test-secret"));
        try {
            migrator.execute("REVOKE SELECT ON forgeoj.login_session FROM 'forgeoj_api'@'%'");
            MvcResult accessFailure = mvc.perform(get(PROTECTED_QUERY).cookie(owner.cookies()))
                    .andExpect(status().isServiceUnavailable()).andReturn();
            assertThat(accessFailure.getResponse().getContentAsString()).isEmpty();

            // Without ACCESS, this reaches the controller and fails its real session lookup.
            MvcResult refreshFailure = write(owner.without(AccountCookies.ACCESS), "refresh", "{}")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value("Authentication temporarily unavailable."))
                    .andReturn();
            Map<String, Object> response = JsonPath.parse(
                    refreshFailure.getResponse().getContentAsString()).read("$");
            assertThat(response).containsOnlyKeys("message");
            assertThat(refreshFailure.getResponse().getContentAsString())
                    .doesNotContain("SELECT", "login_session", "forgeoj_api", "m0-api-test-secret",
                            "m0-migrator-test-secret", owner.cookie(AccountCookies.REFRESH).getValue());
            assertThat(refreshFailure.getResponse().getHeaders("Set-Cookie"))
                    .noneMatch(header -> header.startsWith(AccountCookies.ACCESS + "=")
                            || header.startsWith(AccountCookies.REFRESH + "="));
        } finally {
            migrator.execute("GRANT SELECT ON forgeoj.login_session TO 'forgeoj_api'@'%'");
        }
        assertAuthenticated(owner);
        assertThat(mapper.refresh(AccountSecrets.digest(owner.cookie(AccountCookies.REFRESH).getValue()))
                .orElseThrow().consumedAt()).isNull();
        write(owner.without(AccountCookies.ACCESS), "refresh", "{}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true));
        assertAuthenticated(owner);
    }

    @Test
    void registrationIsGenericAndSessionLoginRefreshResponsesHaveExactWhitelist()
            throws Exception {
        Browser anonymous = anonymous();
        assertSessionWhitelist(mvc.perform(get("/api/v1/auth/session").cookie(anonymous.cookies()))
                .andExpect(status().isOk()).andReturn(), false);
        String registration = "{\"username\":\"HttpWhitelist\",\"email\":\"httpwhitelist@example.test\","
                + "\"password\":\"" + PASSWORD + "\",\"nickname\":\"SameNickname\"}";
        MvcResult first = write(anonymous, "register", registration)
                .andExpect(status().isAccepted()).andReturn();
        MvcResult duplicate = write(anonymous, "register", registration.replace(PASSWORD, NEXT_PASSWORD))
                .andExpect(status().isAccepted()).andReturn();
        Map<String, Object> accepted = JsonPath.parse(first.getResponse().getContentAsString()).read("$");
        assertThat(accepted).containsOnlyKeys("message");
        assertThat(duplicate.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        MvcResult unknownEmail = write(anonymous, "password-reset/request",
                        "{\"email\":\"not-present-whitelist@example.test\"}")
                .andExpect(status().isAccepted()).andReturn();
        assertThat(unknownEmail.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        write(anonymous, "email-verification/confirm",
                        tokenBody(token("httpwhitelist@example.test", "ACTIVATE")))
                .andExpect(status().isNoContent());
        MvcResult loggedIn = loginRequest(anonymous(), "HttpWhitelist", PASSWORD)
                .andExpect(status().isOk()).andReturn();
        assertSessionWhitelist(loggedIn, true);
        Browser owner = browser(loggedIn);
        assertSessionWhitelist(mvc.perform(get("/api/v1/auth/session").cookie(owner.cookies()))
                .andExpect(status().isOk()).andReturn(), true);
        assertSessionWhitelist(write(owner, "refresh", "{}").andExpect(status().isOk()).andReturn(), true);
        loginRequest(anonymous(), "HttpWhitelist", NEXT_PASSWORD)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedAndMissingLoginIdentifiersUseTheSameUnauthorizedResponse() throws Exception {
        Browser anonymous = anonymous();
        for (String identifier : new String[] {"", "@", "name@", "bad..name@example.test",
                "\u4e2d\u6587@example.test", "x".repeat(255), "NotPresentHttpUser"}) {
            MvcResult result = loginRequest(anonymous, identifier, PASSWORD)
                    .andExpect(status().isUnauthorized()).andReturn();
            assertThat(result.getResponse().getContentAsString()).isEmpty();
        }
        MvcResult missing = write(anonymous, "login", "{\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(missing.getResponse().getContentAsString()).isEmpty();
    }

    private void assertSessionWhitelist(MvcResult result, boolean authenticated) throws Exception {
        Map<String, Object> response = JsonPath.parse(result.getResponse().getContentAsString()).read("$");
        assertThat(response).containsOnlyKeys("authenticated", "user", "csrf");
        assertThat(response.get("authenticated")).isEqualTo(authenticated);
        Map<String, Object> csrf = JsonPath.parse(result.getResponse().getContentAsString()).read("$.csrf");
        assertThat(csrf).containsOnlyKeys("headerName", "parameterName", "token");
        if (authenticated) {
            Map<String, Object> user = JsonPath.parse(result.getResponse().getContentAsString()).read("$.user");
            assertThat(user).containsOnlyKeys("id", "username");
        } else {
            assertThat(response.get("user")).isNull();
        }
    }

    private Browser active(String username) throws Exception {
        String email = username.toLowerCase(Locale.ROOT) + "@example.test";
        Browser anonymous = anonymous();
        write(anonymous, "register", "{\"username\":\"" + username + "\",\"email\":\""
                        + email + "\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isAccepted());
        write(anonymous, "email-verification/confirm", tokenBody(token(email, "ACTIVATE")))
                .andExpect(status().isNoContent());
        return login(username, PASSWORD);
    }

    private void requestBinding(Browser owner, String email) throws Exception {
        write(owner, "email-binding/request", "{\"password\":\"" + PASSWORD
                        + "\",\"email\":\"" + email + "\"}")
                .andExpect(status().isAccepted());
    }

    private Browser anonymous() throws Exception {
        return browser(mvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false)).andReturn());
    }

    private Browser login(String identifier, String password) throws Exception {
        MvcResult result = loginRequest(anonymous(), identifier, password)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true)).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        Browser browser = browser(result);
        assertThat(browser.cookies()).extracting(Cookie::getName)
                .contains(AccountCookies.ACCESS, AccountCookies.REFRESH, AccountCookies.CSRF);
        return browser;
    }

    private ResultActions loginRequest(Browser anonymous, String identifier, String password)
            throws Exception {
        return write(anonymous, "login", "{\"username\":\"" + identifier
                + "\",\"password\":\"" + password + "\"}");
    }

    private ResultActions write(Browser browser, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/auth/" + path)
                .cookie(browser.cookies()).header("Origin", ORIGIN)
                .header(browser.csrfHeader(), browser.csrfToken())
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }

    private Browser browser(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return new Browser(result.getResponse().getCookies(),
                JsonPath.read(body, "$.csrf.headerName"), JsonPath.read(body, "$.csrf.token"));
    }

    private void assertAuthenticated(Browser browser) throws Exception {
        mvc.perform(get("/api/v1/auth/session").cookie(browser.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));
        // This fixture has no submissions: 404 proves authentication passed the protected route.
        mvc.perform(get(PROTECTED_QUERY).cookie(browser.cookies())).andExpect(status().isNotFound());
    }

    private void assertRejected(Browser saved) throws Exception {
        assertThat(jwt.verify(saved.cookie(AccountCookies.ACCESS).getValue()).getExpiresAt())
                .as("the saved JWT must still be unexpired; MySQL revocation rejects it")
                .isAfter(Instant.now());
        mvc.perform(get(PROTECTED_QUERY).cookie(saved.cookies())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/session").cookie(saved.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    private String token(String email, String purpose) {
        String link = mail.messages().stream()
                .filter(message -> message.recipient().equals(email) && message.purpose().equals(purpose))
                .findFirst().orElseThrow().link();
        return URI.create(link).getFragment().split("&token=", 2)[1];
    }

    private static String tokenBody(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    private record Browser(Cookie[] cookies, String csrfHeader, String csrfToken) {
        Cookie cookie(String name) {
            return Arrays.stream(cookies).filter(cookie -> name.equals(cookie.getName()))
                    .findFirst().orElseThrow();
        }

        Browser without(String name) {
            return new Browser(Arrays.stream(cookies)
                    .filter(cookie -> !name.equals(cookie.getName())).toArray(Cookie[]::new),
                    csrfHeader, csrfToken);
        }
    }
}
