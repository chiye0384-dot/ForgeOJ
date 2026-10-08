/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.time.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public final class AdminCookies {
    public static final String ACCESS="FORGEOJ_ADMIN_ACCESS",REFRESH="FORGEOJ_ADMIN_REFRESH",CSRF="FORGEOJ_ADMIN_CSRF";
    private final boolean secure;
    public AdminCookies(@Value("${forgeoj.admin.cookie.secure:true}")boolean secure){this.secure=secure;}
    public static String read(HttpServletRequest request,String name){String result=null;if(request.getCookies()!=null)for(var cookie:request.getCookies())if(name.equals(cookie.getName())){if(result!=null)return null;result=cookie.getValue();}return result;}
    static boolean present(HttpServletRequest request,String name){if(request.getCookies()!=null)for(var cookie:request.getCookies())if(name.equals(cookie.getName()))return true;return false;}
    void set(HttpServletResponse response,String name,String value,long seconds){response.addHeader("Set-Cookie",ResponseCookie.from(name,value).path("/api/v1/admin").httpOnly(true).secure(secure).sameSite("Strict").maxAge(seconds).build().toString());}
    void login(HttpServletResponse response,AdminService.Login login,String token){set(response,ACCESS,token,Math.max(0,Math.min(300,Duration.between(Instant.now(),login.expiresAt().toInstant(ZoneOffset.UTC)).toSeconds())));set(response,REFRESH,login.refresh(),Math.max(0,Duration.between(Instant.now(),login.expiresAt().toInstant(ZoneOffset.UTC)).toSeconds()));}
    void clear(HttpServletResponse response){set(response,ACCESS,"",0);set(response,REFRESH,"",0);}
}
