/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.cache;
import java.util.Optional;
import org.apache.ibatis.annotations.*;
@Mapper
public interface SessionCacheMapper {
    record Guard(long id,long version,String role,boolean mustChangePassword) {}
    @Select("SELECT u.id,1 AS version,'' AS role,FALSE AS mustChangePassword FROM user_account u JOIN login_session s ON s.user_id=u.id WHERE s.id=#{sid} AND u.status='ACTIVE' AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP(6)")
    Optional<Guard> ordinary(String sid);
    @Select("SELECT a.id,a.version,a.role,a.must_change_password FROM admin_account a JOIN admin_login_session s ON s.admin_id=a.id WHERE s.id=#{sid} AND a.status='ACTIVE' AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP(6)")
    Optional<Guard> admin(String sid);
    @Select("SELECT username FROM user_account WHERE id=#{id}") String ordinaryName(long id);
    @Select("SELECT username FROM admin_account WHERE id=#{id}") String adminName(long id);
}
