package com.forgeoj.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import jakarta.servlet.http.Cookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/devdata",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "spring.rabbitmq.listener.direct.auto-startup=false"
        })
class AuthAndProblemIntegrationTests {

    private static final String MYSQL_IMAGE =
            "container-registry.oracle.com/mysql/community-server:8.4.12"
                    + "@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be";
    private static final String API_PASSWORD = "m0-api-test-secret";
    private static final String MIGRATOR_PASSWORD = "m0-migrator-test-secret";

    @Container
    static final MySQLContainer MYSQL =
            new com.forgeoj.api.testinfra.DirectMySQLContainer(
                            DockerImageName.parse(MYSQL_IMAGE)
                                    .asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("forgeoj")
                    .withUsername("bootstrap")
                    .withPassword("bootstrap-test-secret")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("mysql/init-test-users.sql"),
                            "/docker-entrypoint-initdb.d/01-init-test-users.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "forgeoj_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "forgeoj_migrator");
        registry.add("spring.flyway.password", () -> MIGRATOR_PASSWORD);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void logsInWithServerSessionAndLogsOut() throws Exception {
        AnonymousSession anonymous = openAnonymousSession();
        assertThat(anonymous.cookies()).extracting(Cookie::getName).contains("FORGEOJ_CSRF");

        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .cookie(anonymous.cookies()).header("Origin","http://localhost")
                                        .header(anonymous.csrfHeader(), anonymous.csrfToken())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "username": "learner",
                                                  "password": "forgeoj-dev-only"
                                                }
                                                """))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.authenticated").value(true))
                        .andExpect(jsonPath("$.user.id").value(1))
                        .andExpect(jsonPath("$.user.username").value("learner"))
                        .andReturn();

        Cookie[] authenticatedSession = loginResult.getResponse().getCookies();
        assertThat(loginResult.getRequest().getSession(false)).isNull();
        assertThat(authenticatedSession).extracting(Cookie::getName).contains("FORGEOJ_ACCESS", "FORGEOJ_REFRESH", "FORGEOJ_CSRF");
        String currentCsrf=JsonPath.read(loginResult.getResponse().getContentAsString(), "$.csrf.token");
        assertThat(currentCsrf).isNotEqualTo(anonymous.csrfToken());

        mockMvc.perform(get("/api/v1/auth/session").cookie(authenticatedSession).header("Origin","http://localhost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.username").value("learner"));

        mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .cookie(authenticatedSession).header("Origin","http://localhost")
                                .header(anonymous.csrfHeader(), currentCsrf))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.user").doesNotExist());
    }

    @Test
    void rejectsMissingCsrfAndInvalidCredentials() throws Exception {
        AnonymousSession missingCsrf = openAnonymousSession();
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .cookie(missingCsrf.cookies()).header("Origin","http://localhost")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"username":"learner","password":"forgeoj-dev-only"}
                                        """))
                .andExpect(status().isForbidden());

        AnonymousSession wrongPassword = openAnonymousSession();
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .cookie(wrongPassword.cookies()).header("Origin","http://localhost")
                                .header(wrongPassword.csrfHeader(), wrongPassword.csrfToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"username":"learner","password":"wrong-password"}
                                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void returnsOnlyWhitelistedPublicProblemFields() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/api/v1/problems/sum-two-integers"))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.slug").value("sum-two-integers"))
                        .andExpect(jsonPath("$.title").value("两数之和"))
                        .andExpect(jsonPath("$.publicSamples[0].input").value("1 2\n"))
                        .andExpect(jsonPath("$.publicSamples[0].output").value("3\n"))
                        .andExpect(jsonPath("$.judgeVersion").value(1))
                        .andExpect(jsonPath("$.resourceLimits.timeLimitMs").value(2000))
                        .andExpect(jsonPath("$.testDatasetSha256").doesNotExist())
                        .andExpect(jsonPath("$.javaImageDigest").doesNotExist())
                        .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody)
                .doesNotContain("2147483647")
                .doesNotContain("37881a92ca996970e09475fdb29435b9bc13ae1501fa118e5fd9afd47e561adf");
    }

    private AnonymousSession openAnonymousSession() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/api/v1/auth/session"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.authenticated").value(false))
                        .andExpect(jsonPath("$.csrf.headerName").value("X-CSRF-TOKEN"))
                        .andExpect(jsonPath("$.csrf.token").isNotEmpty())
                        .andReturn();

        String body = result.getResponse().getContentAsString();
        String headerName = JsonPath.read(body, "$.csrf.headerName");
        String token = JsonPath.read(body, "$.csrf.token");
        Cookie[] session = result.getResponse().getCookies();
        assertThat(session).isNotNull();
        return new AnonymousSession(session, headerName, token);
    }

    private record AnonymousSession(
            Cookie[] cookies, String csrfHeader, String csrfToken) {}
}
