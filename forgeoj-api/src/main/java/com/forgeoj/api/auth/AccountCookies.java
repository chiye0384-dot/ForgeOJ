package com.forgeoj.api.auth;

import jakarta.servlet.http.*;
import java.time.Duration;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AccountCookies {
    public static final String ACCESS="FORGEOJ_ACCESS", REFRESH="FORGEOJ_REFRESH", CSRF="FORGEOJ_CSRF";
    private final boolean secure;
    public AccountCookies(@Value("${forgeoj.auth.cookie.secure:true}") boolean secure){this.secure=secure;}
    public static String read(HttpServletRequest request,String name){
        String value=null; if(request.getCookies()==null)return null;
        for(Cookie cookie:request.getCookies()) if(name.equals(cookie.getName())) {if(value!=null)return null; value=cookie.getValue();}
        return value;
    }
    void login(HttpServletResponse response,AccountService.Login login,String jwt){
        set(response,ACCESS,jwt,300); set(response,REFRESH,login.refresh(),Math.max(0,Duration.between(java.time.Instant.now(),login.expiresAt().toInstant(ZoneOffset.UTC)).toSeconds()));
    }
    void clear(HttpServletResponse response){set(response,ACCESS,"",0);set(response,REFRESH,"",0);}
    void set(HttpServletResponse response,String name,String value,long seconds){ response.addHeader("Set-Cookie",ResponseCookie.from(name,value).path("/").httpOnly(true).secure(secure).sameSite("Strict").maxAge(seconds).build().toString());}
}
