/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtException;

class AdminJwtTests {
    static final String SECRET="public-admin-jwt-unit-signing-key-only-2026",OTHER="public-ordinary-jwt-unit-different-key-only-2026",SID="5d5d6e81-e80b-4b7c-a9ba-70afab983582";
    final AdminJwt verifier=new AdminJwt(new MockEnvironment(),SECRET,OTHER);
    @Test void keyMustBePresentStrongAndIndependent(){for(String key:List.of("","too-short",OTHER))assertThatThrownBy(()->new AdminJwt(new MockEnvironment(),key,OTHER)).isInstanceOf(IllegalStateException.class);}
    @Test void issuedClaimsAreIdentityOnlyAndNeverOutliveSession(){var expires=LocalDateTime.now(ZoneOffset.UTC).plusSeconds(40);var account=new AdminMapper.Account(42,"fixture","not-serialized","ACTIVE","SUPER_ADMIN",false,1);var issued=verifier.verify(verifier.issue(new AdminService.Login(account,SID,"never-serialize-refresh",expires)));assertThat(issued.getClaims()).containsOnlyKeys("sub","sid","iat","exp","iss","aud");assertThat(issued.getExpiresAt()).isBeforeOrEqualTo(expires.toInstant(ZoneOffset.UTC));assertThat(issued.getTokenValue()).doesNotContain("never-serialize-refresh","not-serialized");}
    @Test void issuerAudienceAndSessionIdentityAreExact(){for(var change:List.<Consumer<JWTClaimsSet.Builder>>of(b->b.issuer("forgeoj"),b->b.audience("forgeoj-browser"),b->b.audience(List.of("forgeoj-admin-browser","extra")),b->b.claim("sid","bad"),b->b.subject("0")))assertThatThrownBy(()->verifier.verify(signed(change,SECRET))).isInstanceOf(JwtException.class);}
    @Test void expiredFutureIssuedAndOverlongTokensAreRejected(){Instant now=Instant.now();for(var change:List.<Consumer<JWTClaimsSet.Builder>>of(b->b.expirationTime(Date.from(now.minusSeconds(1))),b->b.issueTime(Date.from(now.plusSeconds(30))),b->b.expirationTime(Date.from(now.plusSeconds(600)))))assertThatThrownBy(()->verifier.verify(signed(change,SECRET))).isInstanceOf(JwtException.class);}
    @Test void wrongKeyCannotAuthenticateAdmin(){assertThatThrownBy(()->verifier.verify(signed(b->{},OTHER))).isInstanceOf(JwtException.class);}
    @Test void malformedTokensNeverAuthenticate(){for(String token:List.of("not-a-jwt","e30.e30.invalid",""))assertThatThrownBy(()->verifier.verify(token)).isInstanceOf(JwtException.class);}
    private String signed(Consumer<JWTClaimsSet.Builder> change,String key)throws Exception{Instant now=Instant.now();var b=new JWTClaimsSet.Builder().issuer("forgeoj-admin").audience("forgeoj-admin-browser").subject("42").claim("sid",SID).issueTime(Date.from(now.minusSeconds(1))).expirationTime(Date.from(now.plusSeconds(120)));change.accept(b);var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),b.build());jwt.sign(new MACSigner(key.getBytes(StandardCharsets.UTF_8)));return jwt.serialize();}
}
