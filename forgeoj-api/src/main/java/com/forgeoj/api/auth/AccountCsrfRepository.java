package com.forgeoj.api.auth;

import jakarta.servlet.http.*;
import org.springframework.security.web.csrf.*;
import org.springframework.stereotype.Component;

/** Host-only HttpOnly cookie plus required request header; no process session authority. */
@Component
public class AccountCsrfRepository implements CsrfTokenRepository {
    private final AccountCookies cookies;
    public AccountCsrfRepository(AccountCookies cookies){this.cookies=cookies;}
    public CsrfToken generateToken(HttpServletRequest request){return new DefaultCsrfToken("X-CSRF-TOKEN","_csrf",AccountSecrets.token());}
    public void saveToken(CsrfToken token,HttpServletRequest request,HttpServletResponse response){cookies.set(response,AccountCookies.CSRF,token==null?"":token.getToken(),token==null?0:7*86400);}
    public CsrfToken loadToken(HttpServletRequest request){String value=AccountCookies.read(request,AccountCookies.CSRF);return value!=null && value.matches("[A-Za-z0-9_-]{43}")?new DefaultCsrfToken("X-CSRF-TOKEN","_csrf",value):null;}
    CsrfToken rotate(HttpServletRequest request,HttpServletResponse response){CsrfToken token=generateToken(request);saveToken(token,request,response);return token;}
}
