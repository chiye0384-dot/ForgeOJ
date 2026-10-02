package com.forgeoj.api.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
public final class AccountJwt {
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    public AccountJwt(Environment environment,@Value("${forgeoj.auth.jwt.secret:}") String secret) {
        if(secret.isEmpty() && environment.matchesProfiles("dev")) secret=AccountSecrets.token();
        if(secret.getBytes(StandardCharsets.UTF_8).length<32) throw new IllegalStateException("Configure forgeoj.auth.jwt.secret with at least 32 random bytes");
        var key=new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256");
        encoder=new NimbusJwtEncoder(new ImmutableSecret<>(key));
        decoder=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamps=new JwtTimestampValidator(java.time.Duration.ZERO);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,new JwtIssuerValidator("forgeoj"),jwt->
            List.of("forgeoj-browser").equals(jwt.getAudience())
                && jwt.getSubject()!=null && jwt.getSubject().matches("[1-9][0-9]{0,19}")
                && jwt.getClaimAsString("sid")!=null && jwt.getClaimAsString("sid").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                && jwt.getExpiresAt()!=null && jwt.getIssuedAt()!=null
                && !jwt.getIssuedAt().isAfter(Instant.now())
                && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                && !jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plusSeconds(300))
                ? OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    }
    String issue(AccountService.Login login) {
        var now=Instant.now();
        var expiry=now.plusSeconds(300); var sessionExpiry=login.expiresAt().toInstant(java.time.ZoneOffset.UTC);
        if(sessionExpiry.isBefore(expiry)) expiry=sessionExpiry;
        var claims=JwtClaimsSet.builder().issuer("forgeoj").audience(List.of("forgeoj-browser")).subject(Long.toString(login.userId()))
            .claim("sid",login.sessionId()).issuedAt(now).expiresAt(expiry).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    public Jwt verify(String token){return decoder.decode(token);}
}
