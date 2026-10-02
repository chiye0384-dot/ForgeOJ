package com.forgeoj.api.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class AccountInput {
    static String username(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{3,32}")) invalid();
        return value;
    }
    static String email(String value) {
        if (value == null) invalid();
        String result = value.strip().toLowerCase(Locale.ROOT);
        if (result.length() > 254 || !result.matches("[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")) invalid();
        return result;
    }
    static String password(String value) {
        if (value == null || value.codePointCount(0, value.length()) < 12
                || value.getBytes(StandardCharsets.UTF_8).length > 72 || value.indexOf('\0') >= 0) invalid();
        return value;
    }
    static String nickname(String value) {
        if (value == null) return null;
        String result = value.strip();
        if (result.length() > 64 || result.codePoints().anyMatch(Character::isISOControl)) invalid();
        return result;
    }
    static void invalid() { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid account input"); }
}
