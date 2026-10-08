/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AdminInput {
    private AdminInput() {}
    static final long MAX_VERSION=9007199254740991L;
    public static String username(String s){if(s==null||!s.matches("[A-Za-z0-9_]{3,32}"))throw error(400);return s.toLowerCase(Locale.ROOT);}
    public static String password(String s){if(s==null||s.codePointCount(0,s.length())<12||s.getBytes(StandardCharsets.UTF_8).length>72||s.indexOf('\0')>=0)throw error(400);return s;}
    static String role(String s){if(!Set.of("CONTENT_REVIEWER","OPS_ADMIN","SUPER_ADMIN").contains(s==null?"":s))throw error(400);return s;}
    public static String reason(String s){if(s==null)throw error(400);s=s.strip();if(s.isEmpty()||s.length()>500||s.codePoints().anyMatch(Character::isISOControl))throw error(400);return s;}
    static String uuid(String s){if(s==null||!s.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw error(400);return s;}
    static void version(long v){if(v<1||v>=MAX_VERSION)throw error(400);}
    static void page(int page,int size){if(page<1||size<1||size>50)throw error(400);}
    static String token(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    static String digest(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA256 unavailable");}}
    static ResponseStatusException error(int code){return new ResponseStatusException(HttpStatus.valueOf(code));}
}
