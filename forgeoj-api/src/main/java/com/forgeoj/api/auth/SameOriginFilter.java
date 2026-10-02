package com.forgeoj.api.auth;

import java.net.URI;
import java.util.Collections;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class SameOriginFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        if(!java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())) {
            var origins=Collections.list(request.getHeaders("Origin"));
            if(origins.size()!=1 || !same(origins.getFirst(),request.getRequestURL().toString())){response.setStatus(403);return;}
        }
        chain.doFilter(request,response);
    }
    public static boolean same(String rawOrigin,String rawTarget) {
        try{URI origin=URI.create(rawOrigin),target=URI.create(rawTarget);
            return ("http".equals(origin.getScheme()) || "https".equals(origin.getScheme())) && origin.getHost()!=null && origin.getUserInfo()==null
                && (origin.getRawPath()==null || origin.getRawPath().isEmpty()) && origin.getRawQuery()==null && origin.getRawFragment()==null
                && origin.getScheme().equalsIgnoreCase(target.getScheme()) && origin.getHost().equalsIgnoreCase(target.getHost()) && port(origin)==port(target);
        }catch(IllegalArgumentException invalid){return false;}
    }
    private static int port(URI uri){return uri.getPort()!=-1?uri.getPort():"https".equals(uri.getScheme())?443:80;}
}
