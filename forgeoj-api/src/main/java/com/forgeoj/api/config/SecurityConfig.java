package com.forgeoj.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.SecurityContextRepository;
import com.forgeoj.api.auth.*;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new NullSecurityContextRepository();
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint() {
        return new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccountCsrfRepository csrfRepository, AccountJwt jwt, com.forgeoj.api.cache.SessionCache sessions)
            throws Exception {
        http.authorizeHttpRequests(
                        authorization ->
                                authorization
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/auth/session",
                                                "/api/v1/problems",
                                                "/api/v1/problem-tags",
                                                "/api/v1/official-problem-lists",
                                                "/api/v1/official-problem-lists/*",
                                                "/api/v1/problems/*")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register",
                                                "/api/v1/auth/email-verification/request", "/api/v1/auth/email-verification/confirm",
                                                "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/confirm",
                                                "/api/v1/auth/refresh", "/api/v1/auth/logout")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .securityContext(
                        security ->
                                security
                                        .securityContextRepository(securityContextRepository)
                                        .requireExplicitSave(true))
                // JWT identity is reconstructed per request. An implicit SessionManagementFilter
                // treats every request as a new login and clears the CSRF cookie in Security 7.1.
                .sessionManagement(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterBefore(new SameOriginFilter(), CsrfFilter.class)
                .addFilterAfter(new AccountAuthenticationFilter(jwt, sessions), SecurityContextHolderFilter.class)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        response.setStatus(
                                                                HttpStatus.FORBIDDEN.value())))
                .logout(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
