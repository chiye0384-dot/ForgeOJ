package com.forgeoj.api.auth;

import static org.assertj.core.api.Assertions.*;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.env.MockEnvironment;

class SmtpAccountMailTests {
    @TempDir static Path certificates;
    private static SSLContext serverTls;
    private static SSLContext trustedClientTls;
    private static final String TOKEN = "T".repeat(43);

    @BeforeAll
    static void createDisposableTlsIdentity() throws Exception {
        // Public, short-lived test credentials. This trust root is used only by injected test senders.
        Path storePath = certificates.resolve("smtp-fixture.p12");
        String keytoolName = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Process tool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", keytoolName).toString(),
                "-genkeypair", "-alias", "fixture", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-keystore", storePath.toString(), "-storetype", "PKCS12", "-storepass", "public-test-only",
                "-keypass", "public-test-only", "-validity", "2", "-keyalg", "RSA", "-keysize", "2048", "-noprompt")
                .redirectErrorStream(true).start();
        if (!tool.waitFor(30, TimeUnit.SECONDS)) {
            tool.destroyForcibly();
            throw new IllegalStateException("Test keytool did not finish");
        }
        assertThat(tool.exitValue()).withFailMessage("Test TLS certificate generation failed").isZero();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(storePath)) {
            store.load(input, "public-test-only".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "public-test-only".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keys.getKeyManagers(), null, null);
        trustedClientTls = SSLContext.getInstance("TLS");
        trustedClientTls.init(null, trust.getTrustManagers(), null);
    }

    @Test
    void requiredStartTlsSendsAllAccountPurposesWithExactEnvelopeAndFragmentLinks() throws Exception {
        try (var fixture = new SmtpFixture(false, false, false, false)) {
            var mail = new SmtpAccountMail(settings(fixture.port(), "starttls"), trustedSender());
            for (String purpose : List.of("ACTIVATE", "RESET_PASSWORD", "BIND_EMAIL")) {
                mail.send("recipient@example.test", purpose, TOKEN);
            }
            assertThat(fixture.tlsHandshakes).hasValue(3);
            assertThat(fixture.messages).hasSize(3);
            assertThat(fixture.commands.stream().filter(command -> command.equals("MAIL FROM:<sender@example.test>"))).hasSize(3);
            assertThat(fixture.commands.stream().filter(command -> command.equals("RCPT TO:<recipient@example.test>"))).hasSize(3);
            List<String> purposes = List.of("ACTIVATE", "RESET_PASSWORD", "BIND_EMAIL");
            for (int index = 0; index < purposes.size(); index++) {
                String purpose = purposes.get(index);
                MimeMessage received = parse(fixture.messages.get(index));
                assertThat(received.getFrom()[0].toString()).isEqualTo("sender@example.test");
                assertThat(received.getAllRecipients()).hasSize(1);
                assertThat(received.getSubject()).isEqualTo(AccountMailContent.subject(purpose));
                assertThat(received.getContentType()).startsWith("text/plain");
                assertThat(received.getContent().toString())
                        .contains(AccountMailContent.link("https://forgeoj.example.test", purpose, TOKEN))
                        .contains("ACTIVATE".equals(purpose) ? "24 小时" : "30 分钟");
                assertThat(received.getHeader("Bcc")).isNull();
            }
        }
    }

    @Test
    void implicitTlsAlsoUsesTrustedMatchingIdentity() throws Exception {
        try (var fixture = new SmtpFixture(true, false, false, false)) {
            var mail = new SmtpAccountMail(settings(fixture.port(), "implicit"), trustedSender());
            mail.send("recipient@example.test", "ACTIVATE", TOKEN);
            assertThat(fixture.messages).hasSize(1);
            assertThat(fixture.tlsHandshakes).hasValue(1);
            assertThat(fixture.commands).noneMatch(command -> command.equals("STARTTLS"));
        }
    }

    @Test
    void serverWithoutStartTlsCannotReceiveCredentialsOrMail() throws Exception {
        try (var fixture = new SmtpFixture(false, true, false, false)) {
            MockEnvironment environment = settings(fixture.port(), "starttls")
                    .withProperty("forgeoj.auth.mail.smtp.username", "fixture-user")
                    .withProperty("forgeoj.auth.mail.smtp.password", "public-fixture-password");
            var mail = new SmtpAccountMail(environment, trustedSender());
            assertThatThrownBy(() -> mail.send("recipient@example.test", "ACTIVATE", TOKEN)).isInstanceOf(MailException.class);
            assertThat(fixture.messages).isEmpty();
            assertThat(fixture.commands).noneMatch(command -> command.startsWith("AUTH") || command.startsWith("MAIL"));
        }
    }

    @Test
    void untrustedCertificateFailsClosed() throws Exception {
        try (var fixture = new SmtpFixture(true, false, false, false)) {
            var mail = new SmtpAccountMail(settings(fixture.port(), "implicit"));
            assertThatThrownBy(() -> mail.send("recipient@example.test", "ACTIVATE", TOKEN)).isInstanceOf(MailException.class);
            assertThat(fixture.commands).isEmpty();
            assertThat(fixture.messages).isEmpty();
        }
    }

    @Test
    void trustedCertificateWithWrongHostStillFailsClosed() throws Exception {
        try (var fixture = new SmtpFixture(true, false, false, false)) {
            var environment = settings(fixture.port(), "implicit").withProperty("forgeoj.auth.mail.smtp.host", "127.0.0.1");
            var mail = new SmtpAccountMail(environment, trustedSender());
            assertThatThrownBy(() -> mail.send("recipient@example.test", "ACTIVATE", TOKEN)).isInstanceOf(MailException.class);
            assertThat(fixture.commands).isEmpty();
            assertThat(fixture.messages).isEmpty();
        }
    }

    @Test
    void rejectedRecipientIsDeliveryFailureAndNoDataIsSent() throws Exception {
        try (var fixture = new SmtpFixture(true, false, true, false)) {
            var mail = new SmtpAccountMail(settings(fixture.port(), "implicit"), trustedSender());
            assertThatThrownBy(() -> mail.send("recipient@example.test", "RESET_PASSWORD", TOKEN)).isInstanceOf(MailException.class);
            assertThat(fixture.messages).isEmpty();
            assertThat(fixture.commands).noneMatch(command -> command.equals("DATA"));
        }
    }

    @Test
    void rejectedAuthenticationDoesNotProceedToEnvelope() throws Exception {
        try (var fixture = new SmtpFixture(true, false, false, true)) {
            var environment = settings(fixture.port(), "implicit")
                    .withProperty("forgeoj.auth.mail.smtp.username", "fixture-user")
                    .withProperty("forgeoj.auth.mail.smtp.password", "public-fixture-password");
            var mail = new SmtpAccountMail(environment, trustedSender());
            assertThatThrownBy(() -> mail.send("recipient@example.test", "BIND_EMAIL", TOKEN)).isInstanceOf(MailException.class);
            assertThat(fixture.commands).anyMatch(command -> command.startsWith("AUTH"));
            assertThat(fixture.commands).noneMatch(command -> command.startsWith("MAIL"));
            assertThat(fixture.messages).isEmpty();
        }
    }

    @Test
    void silentServerIsBoundedByReadTimeout() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> accepted = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(1500);
                    socket.getInputStream().read();
                } catch (IOException expectedDisconnect) { /* fixture intentionally remains silent */ }
            });
            var environment = settings(server.getLocalPort(), "starttls")
                    .withProperty("forgeoj.auth.mail.smtp.read-timeout-ms", "200");
            var mail = new SmtpAccountMail(environment);
            long started = System.nanoTime();
            assertThatThrownBy(() -> mail.send("recipient@example.test", "ACTIVATE", TOKEN)).isInstanceOf(MailException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            accepted.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void missingOrUnsafeConfigurationFailsWithoutEchoingConfiguredValues() {
        for (Map.Entry<String, String> unsafe : Map.ofEntries(
                Map.entry("host", "smtp://secret-user:secret-password@host.test"),
                Map.entry("from", "sender@example.test\r\nBcc: victim@example.test"),
                Map.entry("tls", "none"), Map.entry("connect-timeout-ms", "0"),
                Map.entry("read-timeout-ms", "-1"), Map.entry("write-timeout-ms", "30001"),
                Map.entry("port", "65536"), Map.entry("username", "unpaired-user")).entrySet()) {
            var environment = settings(2525, "starttls").withProperty("forgeoj.auth.mail.smtp." + unsafe.getKey(), unsafe.getValue());
            assertThatThrownBy(() -> new SmtpAccountMail(environment)).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(unsafe.getValue());
        }
        assertThatThrownBy(() -> new SmtpAccountMail(new MockEnvironment())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SmtpAccountMail(settings(2525, "starttls")
                .withProperty("forgeoj.auth.mail.app-url", "http://forgeoj.example.test")))
                .isInstanceOf(IllegalStateException.class);
        for (String invalidUrl : List.of("https://user:password@forgeoj.example.test", "https://forgeoj.example.test/account",
                "https://forgeoj.example.test/?token=secret", "https://forgeoj.example.test/#secret")) {
            assertThatThrownBy(() -> new SmtpAccountMail(settings(2525, "starttls")
                    .withProperty("forgeoj.auth.mail.app-url", invalidUrl))).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(invalidUrl);
        }
    }

    @Test
    void invalidPurposeTokenOrMultipleRecipientCannotOpenSmtpConnection() throws Exception {
        try (var fixture = new SmtpFixture(true, false, false, false)) {
            var mail = new SmtpAccountMail(settings(fixture.port(), "implicit"), trustedSender());
            assertThatThrownBy(() -> mail.send("recipient@example.test", "OTHER", TOKEN)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> mail.send("recipient@example.test", "ACTIVATE", "secret&action=reset")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> mail.send("one@example.test,two@example.test", "ACTIVATE", TOKEN)).isInstanceOf(IllegalStateException.class);
            assertThat(fixture.commands).isEmpty();
            assertThat(fixture.messages).isEmpty();
        }
    }

    @Test
    void defaultDisabledAndExplicitSmtpChooseOneAdapterWithoutConnectingAtStartup() {
        new ApplicationContextRunner().withUserConfiguration(Adapters.class).run(context -> {
            assertThat(context).hasSingleBean(AccountMailDelivery.class).hasSingleBean(DisabledAccountMail.class);
            assertThatThrownBy(() -> context.getBean(AccountMailDelivery.class).send("unused", "unused", "unused"))
                    .isInstanceOf(IllegalStateException.class);
        });
        new ApplicationContextRunner().withUserConfiguration(Adapters.class)
                .withPropertyValues("forgeoj.auth.mail.mode=smtp", "forgeoj.auth.mail.smtp.host=smtp.example.test",
                        "forgeoj.auth.mail.smtp.from=sender@example.test", "forgeoj.auth.mail.app-url=https://forgeoj.example.test")
                .run(context -> assertThat(context).hasSingleBean(AccountMailDelivery.class).hasSingleBean(SmtpAccountMail.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({DisabledAccountMail.class, LocalAccountMail.class, SmtpAccountMail.class})
    static class Adapters {}

    private static MockEnvironment settings(int port, String tls) {
        return new MockEnvironment().withProperty("forgeoj.auth.mail.smtp.host", "localhost")
                .withProperty("forgeoj.auth.mail.smtp.port", Integer.toString(port))
                .withProperty("forgeoj.auth.mail.smtp.tls", tls)
                .withProperty("forgeoj.auth.mail.smtp.from", "sender@example.test")
                .withProperty("forgeoj.auth.mail.app-url", "https://forgeoj.example.test/");
    }

    private static JavaMailSenderImpl trustedSender() {
        var sender = new JavaMailSenderImpl();
        sender.getJavaMailProperties().put("mail.smtp.ssl.socketFactory", trustedClientTls.getSocketFactory());
        return sender;
    }

    private static MimeMessage parse(String message) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(message.getBytes(StandardCharsets.US_ASCII)));
    }

    /** Minimal real SMTP endpoint: only loopback, disposable TLS identity, bounded protocol input. */
    private static final class SmtpFixture implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicInteger tlsHandshakes = new java.util.concurrent.atomic.AtomicInteger();
        private final boolean implicit;
        private final boolean noTls;
        private final boolean rejectRecipient;
        private final boolean rejectAuthentication;

        SmtpFixture(boolean implicit, boolean noTls, boolean rejectRecipient, boolean rejectAuthentication) throws IOException {
            this.implicit = implicit;
            this.noTls = noTls;
            this.rejectRecipient = rejectRecipient;
            this.rejectAuthentication = rejectAuthentication;
            listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            executor.submit(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket socket = listener.accept();
                        sockets.add(socket);
                        executor.submit(() -> conversation(socket));
                    } catch (IOException closed) { break; }
                }
            });
        }

        int port() { return listener.getLocalPort(); }

        private Socket tls(Socket socket) throws IOException {
            SSLSocket secured = (SSLSocket) serverTls.getSocketFactory().createSocket(socket, "localhost", port(), true);
            sockets.add(secured);
            secured.setUseClientMode(false);
            secured.setSoTimeout(2000);
            secured.startHandshake();
            tlsHandshakes.incrementAndGet();
            return secured;
        }

        private void conversation(Socket accepted) {
            Socket socket = accepted;
            try {
                socket.setSoTimeout(2000);
                if (implicit) socket = tls(socket);
                BufferedReader input = reader(socket);
                PrintWriter output = writer(socket);
                output.println("220 localhost ForgeOJ disposable SMTP fixture");
                for (String command; (command = input.readLine()) != null;) {
                    if (command.length() > 4096) throw new IOException("Oversized fixture command");
                    commands.add(command);
                    if (command.startsWith("EHLO")) {
                        output.println("250-localhost");
                        if (!noTls && !(socket instanceof SSLSocket)) output.println("250-STARTTLS");
                        output.println("250 AUTH LOGIN PLAIN");
                    } else if (command.equals("STARTTLS")) {
                        output.println("220 Ready for TLS");
                        socket = tls(socket);
                        input = reader(socket);
                        output = writer(socket);
                    } else if (command.startsWith("AUTH")) {
                        if (rejectAuthentication) output.println("535 Authentication rejected");
                        else if (command.equals("AUTH LOGIN")) {
                            output.println("334 VXNlcm5hbWU6");
                            input.readLine();
                            output.println("334 UGFzc3dvcmQ6");
                            input.readLine();
                            output.println("235 Authenticated");
                        } else output.println("235 Authenticated");
                    } else if (command.startsWith("MAIL FROM:")) output.println("250 Sender accepted");
                    else if (command.startsWith("RCPT TO:")) output.println(rejectRecipient ? "550 Recipient rejected" : "250 Recipient accepted");
                    else if (command.equals("DATA")) {
                        output.println("354 End with a dot");
                        StringBuilder data = new StringBuilder();
                        for (String line; (line = input.readLine()) != null && !line.equals(".");) {
                            if (data.length() > 32768) throw new IOException("Oversized fixture message");
                            data.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                        }
                        messages.add(data.toString());
                        output.println("250 Queued by disposable fixture");
                    } else if (command.equals("QUIT")) { output.println("221 Goodbye"); break; }
                    else if (command.equals("RSET") || command.equals("NOOP")) output.println("250 OK");
                    else output.println("500 Unknown command");
                }
            } catch (IOException expectedOnNegativeTest) {
                // Negative trust/identity/timeout tests deliberately disconnect without SMTP DATA.
            } finally {
                try { socket.close(); } catch (IOException ignored) { }
            }
        }

        private static BufferedReader reader(Socket socket) throws IOException {
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        }

        private static PrintWriter writer(Socket socket) throws IOException {
            return new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
        }

        @Override public void close() throws Exception {
            listener.close();
            for (Socket socket : sockets) socket.close();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }
}
