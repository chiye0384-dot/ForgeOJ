/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import com.forgeoj.api.auth.*;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AdminAuthenticationFilter extends OncePerRequestFilter {
    private final AdminJwt jwt;private final AccountJwt ordinaryJwt;private final com.forgeoj.api.cache.SessionCache sessions;
    public AdminAuthenticationFilter(AdminJwt jwt,AccountJwt ordinaryJwt,com.forgeoj.api.cache.SessionCache sessions){this.jwt=jwt;this.ordinaryJwt=ordinaryJwt;this.sessions=sessions;}
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain)throws ServletException,IOException{
        s.setHeader("Cache-Control","no-store");
        try{
            String token=AdminCookies.read(r,AdminCookies.ACCESS);
            if(AdminCookies.present(r,AdminCookies.ACCESS)){
                if(token!=null&&token.length()<=2048){var claims=jwt.verify(token);var a=sessions.admin(Long.parseLong(claims.getSubject()),claims.getClaimAsString("sid"));if(a.isPresent()){var p=a.get();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p,null,List.of(new SimpleGrantedAuthority("ADMIN"))));}}
            }else{
                String userToken=AccountCookies.read(r,AccountCookies.ACCESS);
                if(userToken!=null&&userToken.length()<=2048){var claims=ordinaryJwt.verify(userToken);var a=sessions.ordinary(Long.parseLong(claims.getSubject()),claims.getClaimAsString("sid"));if(a.isPresent()){var p=a.get();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p,null,List.of()));}}
            }
        }catch(JwtException|IllegalArgumentException invalid){SecurityContextHolder.clearContext();}catch(DataAccessException unavailable){s.setStatus(503);return;}
        chain.doFilter(r,s);
    }
}
