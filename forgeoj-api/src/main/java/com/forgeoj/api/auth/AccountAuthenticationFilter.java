package com.forgeoj.api.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AccountAuthenticationFilter extends OncePerRequestFilter {
    private final AccountJwt jwt; private final com.forgeoj.api.cache.SessionCache sessions;
    public AccountAuthenticationFilter(AccountJwt jwt,com.forgeoj.api.cache.SessionCache sessions){this.jwt=jwt;this.sessions=sessions;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String token=AccountCookies.read(request,AccountCookies.ACCESS);
        if(token!=null && token.length()<=2048) {
            try {
                var claims=jwt.verify(token); long id=Long.parseLong(claims.getSubject());String sid=claims.getClaimAsString("sid");
                var account=sessions.ordinary(id,sid);
                if(account.isPresent()){
                    var principal=account.get();
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,List.of()));
                }
            } catch(JwtException | IllegalArgumentException invalid){SecurityContextHolder.clearContext();}
            catch(org.springframework.dao.DataAccessException unavailable){SecurityContextHolder.clearContext();response.setHeader("Cache-Control","no-store");response.setStatus(503);return;}
        }
        chain.doFilter(request,response);
    }
}
