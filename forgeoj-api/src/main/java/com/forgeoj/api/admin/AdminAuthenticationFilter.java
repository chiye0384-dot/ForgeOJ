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
    private final AdminJwt jwt;private final AdminMapper mapper;private final AccountJwt ordinaryJwt;private final AccountMapper ordinary;
    public AdminAuthenticationFilter(AdminJwt jwt,AdminMapper mapper,AccountJwt ordinaryJwt,AccountMapper ordinary){this.jwt=jwt;this.mapper=mapper;this.ordinaryJwt=ordinaryJwt;this.ordinary=ordinary;}
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain)throws ServletException,IOException{
        s.setHeader("Cache-Control","no-store");
        try{
            String token=AdminCookies.read(r,AdminCookies.ACCESS);
            if(AdminCookies.present(r,AdminCookies.ACCESS)){
                if(token!=null&&token.length()<=2048){var claims=jwt.verify(token);var a=mapper.authenticated(claims.getClaimAsString("sid"));if(a.isPresent()&&a.get().id()==Long.parseLong(claims.getSubject())){var account=a.get();var p=new AdminPrincipal(account.id(),account.username(),account.role(),account.mustChangePassword(),claims.getClaimAsString("sid"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p,null,List.of(new SimpleGrantedAuthority("ADMIN"))));}}
            }else{
                String userToken=AccountCookies.read(r,AccountCookies.ACCESS);
                if(userToken!=null&&userToken.length()<=2048){var claims=ordinaryJwt.verify(userToken);var a=ordinary.authenticated(claims.getClaimAsString("sid"));if(a.isPresent()&&a.get().id()==Long.parseLong(claims.getSubject())){var p=new ForgeOjPrincipal(a.get().id(),a.get().username(),"",true,claims.getClaimAsString("sid"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p,null,List.of()));}}
            }
        }catch(JwtException|IllegalArgumentException invalid){SecurityContextHolder.clearContext();}catch(DataAccessException unavailable){s.setStatus(503);return;}
        chain.doFilter(r,s);
    }
}
