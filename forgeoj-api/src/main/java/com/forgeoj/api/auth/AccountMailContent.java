package com.forgeoj.api.auth;

import java.net.URI;
import java.util.List;

final class AccountMailContent {
    private AccountMailContent() {}

    static String applicationUrl(String value, boolean requireHttps) {
        try {
            URI uri = URI.create(value);
            if (!List.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))
                    || (requireHttps && !"https".equals(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        } catch (RuntimeException invalid) {
            // Never include a configured URI: it could contain accidental credentials.
            throw new IllegalStateException("Invalid mail application URL");
        }
    }

    static String link(String baseUrl, String purpose, String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid account mail token");
        }
        String action = switch (purpose) {
            case "ACTIVATE" -> "activate";
            case "RESET_PASSWORD" -> "reset";
            case "BIND_EMAIL" -> "bind";
            default -> throw new IllegalArgumentException("Unknown mail purpose");
        };
        return baseUrl + "/account#action=" + action + "&token=" + token;
    }

    static String subject(String purpose) {
        return switch (purpose) {
            case "ACTIVATE" -> "ForgeOJ 邮箱验证";
            case "RESET_PASSWORD" -> "ForgeOJ 密码重置";
            case "BIND_EMAIL" -> "ForgeOJ 邮箱绑定";
            default -> throw new IllegalArgumentException("Unknown mail purpose");
        };
    }

    static String text(String link, String purpose) {
        String validity = "ACTIVATE".equals(purpose) ? "24 小时" : "30 分钟";
        return "请打开以下链接完成 ForgeOJ 操作：\n\n" + link
                + "\n\n链接有效期为 " + validity + "，只能使用一次。"
                + "\n如果这不是您的请求，请忽略此邮件。\n";
    }
}
