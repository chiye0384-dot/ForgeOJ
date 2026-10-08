/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.io.IOException;
import java.util.Collections;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import com.forgeoj.api.auth.SameOriginFilter;

/** The ordinary domain keeps its own filter; admin denial requires bounded durable audit. */
final class AdminOriginFilter extends OncePerRequestFilter {
    private final AdminAudit audit;
    private final AdminService service;
    AdminOriginFilter(AdminAudit audit,AdminService service){this.audit=audit;this.service=service;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        if(!java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())){
            var origins=Collections.list(request.getHeaders("Origin"));
            if(origins.size()!=1||!SameOriginFilter.same(origins.getFirst(),request.getRequestURL().toString())){AdminSecurityConfig.deny(request,response,403,audit,service);return;}
        }
        chain.doFilter(request,response);
    }
}
