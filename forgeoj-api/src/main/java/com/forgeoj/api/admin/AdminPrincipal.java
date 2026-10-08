/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

public record AdminPrincipal(long id,String username,String role,boolean mustChangePassword,String sessionId) {}
