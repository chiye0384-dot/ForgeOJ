/* Copyright 2026 池也. SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Public fixture keys; signatures use the existing Spring/Nimbus implementation. */
class AccountJwtTests {
    private static final String SECRET =
            "public-test-only-signing-key-never-use-outside-tests-0000000000000000";
    private static final String OTHER_SECRET =
            "different-public-test-only-signing-key-do-not-use-in-production-00000";
    private static final String SID = "5d5d6e81-e80b-4b7c-a9ba-70afab983582";
    private final AccountJwt verifier = new AccountJwt(new MockEnvironment(), SECRET);

    @Test
    void acceptsValidHs256TokenWithExactIdentityAndAudience() {
        var token = verifier.verify(signed(builder -> {}));
        assertThat(token.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(token.getClaimAsString("iss")).isEqualTo("forgeoj");
        assertThat(token.getAudience()).containsExactly("forgeoj-browser");
        assertThat(token.getSubject()).isEqualTo("42");
        assertThat(token.getClaimAsString("sid")).isEqualTo(SID);
    }

    @Test
    void issuedTokenContainsOnlyRequiredClaimsAndHasAtMostFiveMinutesLifetime() {
        var expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusDays(7);
        var login = new AccountService.Login(42, "JwtFixture", SID, "not-serialized", expiresAt);
        var token = verifier.verify(verifier.issue(login));
        assertThat(token.getClaims().keySet())
                .containsExactlyInAnyOrder("sub", "sid", "iat", "exp", "iss", "aud");
        assertThat(token.getExpiresAt()).isAfter(token.getIssuedAt())
                .isBeforeOrEqualTo(token.getIssuedAt().plusSeconds(300));
        assertThat(token.getTokenValue()).doesNotContain("JwtFixture", "not-serialized");
    }

    @Test
    void issuedTokenDoesNotOutliveItsDatabaseSession() {
        var expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(40);
        var login = new AccountService.Login(42, "JwtFixture", SID, "not-serialized", expiresAt);
        var token = verifier.verify(verifier.issue(login));
        assertThat(token.getExpiresAt()).isBeforeOrEqualTo(expiresAt.toInstant(ZoneOffset.UTC));
    }

    @Test
    void rejectsExpiredTokenWithoutClockSkewAllowance() {
        Instant now = Instant.now();
        assertRejected(signed(builder -> builder.issuedAt(now.minusSeconds(240))
                .expiresAt(now.minusSeconds(1))));
    }

    @Test
    void rejectsNotYetValidToken() {
        assertRejected(signed(builder -> builder.notBefore(Instant.now().plusSeconds(60))));
    }

    @Test
    void rejectsFutureIssuedAtEvenWhenExpirationIsWithinFiveMinutesOfIt() {
        Instant now = Instant.now();
        assertRejected(signed(builder -> builder.issuedAt(now.plusSeconds(60))
                .expiresAt(now.plusSeconds(120))));
    }

    @Test
    void rejectsExpirationBeforeIssuedAt() throws Exception {
        Instant now = Instant.now();
        // Spring's encoder prevents this fixture; Nimbus signs it without a Spring Jwt wrapper.
        var invalidClaims = new JWTClaimsSet.Builder()
                .issuer("forgeoj").audience("forgeoj-browser").subject("42").claim("sid", SID)
                .issueTime(Date.from(now.minusSeconds(60)))
                .expirationTime(Date.from(now.minusSeconds(120))).build();
        var invalidToken = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), invalidClaims);
        invalidToken.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        assertRejected(invalidToken.serialize());
    }

    @Test
    void rejectsLifetimeLongerThanFiveMinutes() {
        Instant now = Instant.now();
        assertRejected(signed(builder -> builder.issuedAt(now.minusSeconds(1))
                .expiresAt(now.plusSeconds(300))));
    }

    @Test
    void rejectsIncorrectIssuerAndIncorrectOrAdditionalAudience() {
        assertRejected(signed(builder -> builder.issuer("another-issuer")));
        assertRejected(signed(builder -> builder.audience(List.of("another-browser"))));
        assertRejected(signed(builder -> builder.audience(List.of("forgeoj-browser", "another-browser"))));
    }

    @Test
    void rejectsMissingMandatoryClaims() {
        for (String claim : List.of("sid", "sub", "iat", "exp", "iss", "aud")) {
            String token = signed(builder -> builder.claims(claims -> claims.remove(claim)));
            assertThatThrownBy(() -> verifier.verify(token))
                    .as("missing claim %s must fail verification", claim)
                    .isInstanceOf(JwtException.class);
        }
    }

    @Test
    void rejectsInvalidSubjectAndNonCanonicalSessionId() {
        for (String subject : List.of("", "0", "-1", "+1", "01", "user42", "1".repeat(21))) {
            assertRejected(signed(builder -> builder.subject(subject)));
        }
        for (String sid : List.of("", "not-a-session", SID.toUpperCase(java.util.Locale.ROOT))) {
            assertRejected(signed(builder -> builder.claim("sid", sid)));
        }
    }

    @Test
    void rejectsCorrectlySignedTokenUsingAnotherAlgorithm() {
        assertRejected(signed(validClaims(), SECRET, MacAlgorithm.HS384));
    }

    @Test
    void rejectsAnotherSigningKeyAndTamperedSignatureOrPayload() {
        assertRejected(signed(validClaims(), OTHER_SECRET, MacAlgorithm.HS256));
        String original = signed(builder -> {});
        String[] parts = original.split("\\.", -1);
        String modifiedSignature = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        assertRejected(parts[0] + "." + parts[1] + "." + modifiedSignature);
        String alteredPayload = signed(builder -> builder.subject("43")).split("\\.", -1)[1];
        assertRejected(parts[0] + "." + alteredPayload + "." + parts[2]);
    }

    @Test
    void rejectsMalformedCompactJwt() {
        for (String token : List.of("not-a-jwt", "one.two", "one.two.three.four", "..")) {
            assertRejected(token);
        }
    }

    @Test
    void requiresConfiguredSigningSecretOutsideDevelopment() {
        assertThatThrownBy(() -> new AccountJwt(new MockEnvironment(), ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AccountJwt(new MockEnvironment(), "short-public-test-key"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static JwtClaimsSet.Builder validClaims() {
        Instant now = Instant.now();
        return JwtClaimsSet.builder().issuer("forgeoj").audience(List.of("forgeoj-browser"))
                .subject("42").claim("sid", SID).issuedAt(now.minusSeconds(1))
                .expiresAt(now.plusSeconds(120));
    }

    private static String signed(Consumer<JwtClaimsSet.Builder> change) {
        var claims = validClaims();
        change.accept(claims);
        return signed(claims, SECRET, MacAlgorithm.HS256);
    }

    private static String signed(JwtClaimsSet.Builder claims, String secret, MacAlgorithm algorithm) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),
                algorithm.equals(MacAlgorithm.HS384) ? "HmacSHA384" : "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(algorithm).build(), claims.build()))
                .getTokenValue();
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(JwtException.class);
    }
}
