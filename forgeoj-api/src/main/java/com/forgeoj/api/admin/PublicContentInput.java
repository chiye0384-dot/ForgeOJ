/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** Reject omitted versions, fractional numbers and extraneous mutation fields. */
public final class PublicContentInput {
    private PublicContentInput() {}
    public static void keys(Map<String,Object> body,String... keys){if(!body.keySet().equals(Set.of(keys)))throw bad();}
    public static long integer(Map<String,Object> body,String key){var value=body.get(key);if(!(value instanceof Integer||value instanceof Long))throw bad();return ((Number)value).longValue();}
    public static String string(Map<String,Object> body,String key){if(!(body.get(key) instanceof String value))throw bad();return value;}
    private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST);}
}
