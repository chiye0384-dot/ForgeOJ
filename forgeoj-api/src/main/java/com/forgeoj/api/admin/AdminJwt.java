/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
public final class AdminJwt {
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    public AdminJwt(Environment env,@Value("${forgeoj.admin.jwt.secret:}")String secret,@Value("${forgeoj.auth.jwt.secret:}")String ordinary){
        if(secret.isEmpty()&&env.matchesProfiles("dev"))secret=AdminInput.token();
        if(secret.getBytes(StandardCharsets.UTF_8).length<32||secret.equals(ordinary))throw new IllegalStateException("Configure an independent forgeoj.admin.jwt.secret with at least 32 random bytes");
        var key=new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256");encoder=new NimbusJwtEncoder(new ImmutableSecret<>(key));decoder=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),new JwtIssuerValidator("forgeoj-admin"),jwt->
            List.of("forgeoj-admin-browser").equals(jwt.getAudience())&&jwt.getSubject()!=null&&jwt.getSubject().matches("[1-9][0-9]{0,15}")&&jwt.getClaimAsString("sid")!=null&&jwt.getClaimAsString("sid").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")&&jwt.getExpiresAt()!=null&&jwt.getIssuedAt()!=null&&!jwt.getIssuedAt().isAfter(Instant.now())&&jwt.getExpiresAt().isAfter(jwt.getIssuedAt())&&!jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plusSeconds(300))?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    }
    public String issue(AdminService.Login login){var now=Instant.now();var expiry=now.plusSeconds(300);var end=login.expiresAt().toInstant(ZoneOffset.UTC);if(end.isBefore(expiry))expiry=end;var claims=JwtClaimsSet.builder().issuer("forgeoj-admin").audience(List.of("forgeoj-admin-browser")).subject(Long.toString(login.account().id())).claim("sid",login.sessionId()).issuedAt(now).expiresAt(expiry).build();return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();}
    public Jwt verify(String token){return decoder.decode(token);}
}
