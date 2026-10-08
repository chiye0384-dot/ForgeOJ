/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import jakarta.servlet.http.*;
import org.springframework.security.web.csrf.*;
import org.springframework.stereotype.Component;

@Component
public final class AdminCsrfRepository implements CsrfTokenRepository {
    private final AdminCookies cookies;
    public AdminCsrfRepository(AdminCookies cookies){this.cookies=cookies;}
    public CsrfToken generateToken(HttpServletRequest r){return new DefaultCsrfToken("X-ADMIN-CSRF-TOKEN","_admin_csrf",AdminInput.token());}
    public void saveToken(CsrfToken token,HttpServletRequest r,HttpServletResponse s){cookies.set(s,AdminCookies.CSRF,token==null?"":token.getToken(),token==null?0:8*3600);}
    public CsrfToken loadToken(HttpServletRequest r){String token=AdminCookies.read(r,AdminCookies.CSRF);return token!=null&&token.matches("[A-Za-z0-9_-]{43}")?new DefaultCsrfToken("X-ADMIN-CSRF-TOKEN","_admin_csrf",token):null;}
    CsrfToken rotate(HttpServletRequest r,HttpServletResponse s){var token=generateToken(r);saveToken(token,r,s);return token;}
}
