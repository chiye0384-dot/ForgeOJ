/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import com.forgeoj.api.auth.*;

@Configuration
public class AdminSecurityConfig {
    @Bean @Order(1)
    SecurityFilterChain adminSecurity(HttpSecurity http,AdminCsrfRepository csrf,AdminJwt jwt,AccountJwt userJwt,com.forgeoj.api.cache.SessionCache sessions,AdminAudit audit,AdminService service)throws Exception{
        http.securityMatcher("/api/v1/admin/**")
            .authorizeHttpRequests(a->a.requestMatchers(HttpMethod.GET,"/api/v1/admin/auth/session").permitAll().requestMatchers(HttpMethod.POST,"/api/v1/admin/auth/login","/api/v1/admin/auth/refresh","/api/v1/admin/auth/logout").permitAll().anyRequest().hasAuthority("ADMIN"))
            .securityContext(s->s.securityContextRepository(new NullSecurityContextRepository()).requireExplicitSave(true))
            .sessionManagement(AbstractHttpConfigurer::disable)
            .csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .addFilterBefore(new AdminOriginFilter(audit,service),CsrfFilter.class)
            .addFilterAfter(new AdminAuthenticationFilter(jwt,userJwt,sessions),SecurityContextHolderFilter.class)
            .exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->deny(r,s,401,audit,service)).accessDeniedHandler((r,s,x)->deny(r,s,403,audit,service)))
            .requestCache(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable);
        return http.build();
    }
    static void deny(jakarta.servlet.http.HttpServletRequest r,jakarta.servlet.http.HttpServletResponse s,int status,AdminAudit audit,AdminService service){
        try{service.rate("denied-ip:"+r.getRemoteAddr(),30,300);audit.denied(status);s.setStatus(status);}catch(org.springframework.web.server.ResponseStatusException limited){s.setStatus(limited.getStatusCode().value());}catch(org.springframework.dao.DataAccessException unavailable){s.setStatus(503);}
        s.setHeader("Cache-Control","no-store");
    }
}
