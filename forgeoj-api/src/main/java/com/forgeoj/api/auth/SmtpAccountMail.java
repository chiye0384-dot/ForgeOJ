package com.forgeoj.api.auth;

import jakarta.mail.MessagingException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Explicit SMTP mode with required TLS, normal trust validation and bounded socket waits. */
@Component
@ConditionalOnProperty(name = "forgeoj.auth.mail.mode", havingValue = "smtp")
public final class SmtpAccountMail implements AccountMailDelivery {
    private static final String PREFIX = "forgeoj.auth.mail.smtp.";
    private final JavaMailSenderImpl sender;
    private final String from;
    private final String baseUrl;

    @Autowired
    public SmtpAccountMail(Environment environment) {
        this(environment, new JavaMailSenderImpl());
    }

    // The supplied sender is package-scoped for isolated protocol tests; production creates its own.
    SmtpAccountMail(Environment environment, JavaMailSenderImpl sender) {
        this.sender = sender;
        String host = required(environment, "host");
        if (!host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")) invalid();
        String tls = environment.getProperty(PREFIX + "tls", "starttls");
        if (!List.of("starttls", "implicit").contains(tls)) invalid();
        from = address(required(environment, "from"));
        String appUrl = environment.getProperty("forgeoj.auth.mail.app-url", "");
        boolean fixture = environment.matchesProfiles("dev", "test");
        baseUrl = AccountMailContent.applicationUrl(appUrl, !fixture);
        if ("http".equals(URI.create(baseUrl).getScheme())
                && !List.of("localhost", "127.0.0.1", "[::1]").contains(URI.create(baseUrl).getHost())) {
            invalid();
        }
        String username = environment.getProperty(PREFIX + "username", "");
        String password = environment.getProperty(PREFIX + "password", "");
        // Auth is opt-in as a complete pair; some private relays use network authorization.
        if (username.isBlank() != password.isBlank()
                || username.codePoints().anyMatch(Character::isISOControl)) invalid();
        sender.setHost(host);
        sender.setPort(number(environment, "port", "implicit".equals(tls) ? 465 : 587, 1, 65535));
        sender.setProtocol("smtp");
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        if (!username.isBlank()) {
            sender.setUsername(username);
            sender.setPassword(password);
        }
        Properties properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth", Boolean.toString(!username.isBlank()));
        properties.setProperty("mail.smtp.from", from);
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString("starttls".equals(tls)));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString("starttls".equals(tls)));
        properties.setProperty("mail.smtp.ssl.enable", Boolean.toString("implicit".equals(tls)));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.ssl.protocols", "TLSv1.3 TLSv1.2");
        properties.setProperty("mail.smtp.connectiontimeout", Integer.toString(number(environment, "connect-timeout-ms", 5000, 100, 30000)));
        properties.setProperty("mail.smtp.timeout", Integer.toString(number(environment, "read-timeout-ms", 5000, 100, 30000)));
        properties.setProperty("mail.smtp.writetimeout", Integer.toString(number(environment, "write-timeout-ms", 5000, 100, 30000)));
        properties.setProperty("mail.smtp.quitwait", "false");
        properties.setProperty("mail.smtp.sendpartial", "false");
        properties.setProperty("mail.debug", "false");
        properties.setProperty("mail.debug.auth", "false");
    }

    @Override
    public void send(String recipient, String purpose, String token) {
        String target = address(recipient);
        String link = AccountMailContent.link(baseUrl, purpose, token);
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setValidateAddresses(true);
            helper.setFrom(from);
            helper.setTo(target);
            helper.setSubject(AccountMailContent.subject(purpose));
            helper.setText(AccountMailContent.text(link, purpose), false);
            sender.send(message);
        } catch (MessagingException failure) {
            // AccountService catches delivery failure and logs only its fixed code.
            throw new MailPreparationException("Account mail preparation failed");
        }
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(PREFIX + name, "");
        if (value.isBlank()) invalid();
        return value;
    }

    private static String address(String value) {
        try {
            String result = AccountInput.email(value);
            if (!result.equals(value)) invalid();
            return result;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Invalid SMTP address configuration");
        }
    }

    private static int number(Environment environment, String name, int fallback, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(environment.getProperty(PREFIX + name, Integer.toString(fallback)));
            if (value < minimum || value > maximum) invalid();
            return value;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Invalid SMTP numeric configuration");
        }
    }

    private static void invalid() {
        throw new IllegalStateException("Invalid SMTP configuration");
    }
}
