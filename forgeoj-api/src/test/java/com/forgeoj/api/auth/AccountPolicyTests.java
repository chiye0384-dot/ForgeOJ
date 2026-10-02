package com.forgeoj.api.auth;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AccountPolicyTests {
    @Test void rejectsAmbiguousIdentifiersAndBcryptTruncation() {
        assertThat(AccountInput.email(" First@Example.test ")).isEqualTo("first@example.test");
        assertThatThrownBy(()->AccountInput.username("中文用户")).hasMessageContaining("400");
        assertThatThrownBy(()->AccountInput.username("a@b")).hasMessageContaining("400");
        assertThatThrownBy(()->AccountInput.email("a..b@example.test")).hasMessageContaining("400");
        assertThatThrownBy(()->AccountInput.password("汉".repeat(25))).hasMessageContaining("400");
        assertThat(AccountInput.password(" "+"a".repeat(12)+" ")).startsWith(" ").endsWith(" ");
    }
    @Test void originRequiresExactSchemeHostPortWithoutUserInfoOrPaths() {
        assertThat(SameOriginFilter.same("http://localhost:5173","http://localhost:5173/api/v1/auth/login")).isTrue();
        for(String origin:new String[]{"null","http://evil.test","http://localhost","https://localhost:5173","http://localhost:5173/","http://user@localhost:5173"})
            assertThat(SameOriginFilter.same(origin,"http://localhost:5173/api/v1/auth/login")).isFalse();
    }
    @Test void rateLimitRejectsBeforeTheNextAttemptAndHasNoUnboundedEvictionBypass() {
        var limiter=new AccountRateLimiter(1);for(int i=0;i<3;i++)limiter.check("test",3,300);
        assertThatThrownBy(()->limiter.check("test",3,300)).hasMessageContaining("429");
        for(int i=0;i<9999;i++)limiter.check("other-"+i,1,300);
        assertThatThrownBy(()->limiter.check("overflow",1,300)).hasMessageContaining("429");
    }
}
