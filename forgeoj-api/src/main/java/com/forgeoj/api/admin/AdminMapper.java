/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AdminMapper {
    record Account(long id,String username,String passwordHash,String status,String role,boolean mustChangePassword,long version) {}
    record Session(String id,long adminId,LocalDateTime expiresAt,LocalDateTime revokedAt) {}
    record Refresh(String tokenSha256,String sessionId,LocalDateTime consumedAt) {}
    record Creation(long targetId,String publicRequestSha256) {}
    record Summary(long id,String username,String status,String role,boolean mustChangePassword,long version) {}
    record Event(String id,LocalDateTime occurredAt,String actorType,Long actorAdminId,String action,String targetType,String targetId,String outcome,String reason,String beforeState,String afterState,String correlationId) {}
    String C="id,username,password_hash,status,role,must_change_password,version";
    @Select("SELECT id FROM admin_policy_fence WHERE id=1 FOR UPDATE") int fence();
    @Select("SELECT UTC_TIMESTAMP(6)") LocalDateTime now();
    @Select("SELECT "+C+" FROM admin_account WHERE username=#{name}") Optional<Account> find(String name);
    @Select("SELECT "+C+" FROM admin_account WHERE id=#{id} FOR UPDATE") Optional<Account> lock(long id);
    @Select("SELECT a.id,a.username,a.password_hash,a.status,a.role,a.must_change_password,a.version FROM admin_account a JOIN admin_login_session s ON s.admin_id=a.id WHERE s.id=#{sid} AND a.status='ACTIVE' AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP(6)") Optional<Account> authenticated(String sid);
    @Select("SELECT id,admin_id,expires_at,revoked_at FROM admin_login_session WHERE id=#{sid}") Optional<Session> session(String sid);
    @Select("SELECT id,admin_id,expires_at,revoked_at FROM admin_login_session WHERE id=#{sid} FOR UPDATE") Optional<Session> lockSession(String sid);
    @Insert("INSERT INTO admin_account(username,password_hash,status,role,must_change_password,created_at,updated_at) VALUES(#{name},#{hash},'ACTIVE',#{role},TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))") void insert(@Param("name")String name,@Param("hash")String hash,@Param("role")String role);
    @Select("SELECT LAST_INSERT_ID()") long inserted();
    @Select("SELECT COUNT(*) FROM admin_account WHERE status='ACTIVE' AND role='SUPER_ADMIN'") int activeSupers();
    @Select("SELECT COUNT(*) FROM admin_account") long count();
    @Select("SELECT id,username,status,role,must_change_password,version FROM admin_account ORDER BY id LIMIT #{size} OFFSET #{offset}") List<Summary> list(@Param("size")int size,@Param("offset")long offset);
    @Update("UPDATE admin_account SET status=#{status},role=#{role},password_hash=#{hash},must_change_password=#{must},version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND version=#{version}") int update(@Param("id")long id,@Param("version")long version,@Param("status")String status,@Param("role")String role,@Param("hash")String hash,@Param("must")boolean must);
    @Insert("INSERT INTO admin_login_session(id,admin_id,created_at,expires_at) VALUES(#{id},#{adminId},UTC_TIMESTAMP(6),#{expiresAt})") void insertSession(Session session);
    @Update("UPDATE admin_login_session SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(6)) WHERE id=#{sid}") void revoke(String sid);
    @Update("UPDATE admin_login_session SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(6)) WHERE admin_id=#{id}") void revokeAll(long id);
    @Insert("INSERT INTO admin_refresh_token(token_sha256,session_id,created_at) VALUES(#{hash},#{sid},UTC_TIMESTAMP(6))") void insertRefresh(@Param("hash")String hash,@Param("sid")String sid);
    @Select("SELECT token_sha256,session_id,consumed_at FROM admin_refresh_token WHERE token_sha256=#{hash}") Optional<Refresh> refresh(String hash);
    @Select("SELECT token_sha256,session_id,consumed_at FROM admin_refresh_token WHERE token_sha256=#{hash} FOR UPDATE") Optional<Refresh> lockRefresh(String hash);
    @Update("UPDATE admin_refresh_token SET consumed_at=UTC_TIMESTAMP(6) WHERE token_sha256=#{hash} AND consumed_at IS NULL") int consume(String hash);
    @Select("SELECT target_id,public_request_sha256 FROM admin_creation_request WHERE actor_id=#{actor} AND client_request_id=#{request}") Optional<Creation> creation(@Param("actor")long actor,@Param("request")String request);
    @Insert("INSERT INTO admin_creation_request(actor_id,client_request_id,target_id,public_request_sha256) VALUES(#{actor},#{request},#{target},#{hash})") void creationRequest(@Param("actor")long actor,@Param("request")String request,@Param("target")long target,@Param("hash")String hash);
    @Insert("INSERT INTO admin_audit_event(id,occurred_at,actor_type,actor_admin_id,action,target_type,target_id,outcome,reason,before_state,after_state,correlation_id) VALUES(#{id},UTC_TIMESTAMP(6),#{actorType},#{actorAdminId},#{action},#{targetType},#{targetId},#{outcome},#{reason},#{beforeState},#{afterState},#{correlationId})") void audit(Event event);
    String WHERE=" WHERE (#{action} IS NULL OR action=#{action}) AND (#{actor} IS NULL OR actor_admin_id=#{actor}) AND (#{target} IS NULL OR target_id=#{target}) AND (#{from} IS NULL OR occurred_at>=#{from}) AND (#{to} IS NULL OR occurred_at<#{to})";
    @Select("SELECT COUNT(*) FROM admin_audit_event"+WHERE) long eventCount(@Param("action")String action,@Param("actor")Long actor,@Param("target")String target,@Param("from")LocalDateTime from,@Param("to")LocalDateTime to);
    @Select("SELECT * FROM admin_audit_event"+WHERE+" ORDER BY occurred_at DESC,id DESC LIMIT #{size} OFFSET #{offset}") List<Event> events(@Param("action")String action,@Param("actor")Long actor,@Param("target")String target,@Param("from")LocalDateTime from,@Param("to")LocalDateTime to,@Param("size")int size,@Param("offset")long offset);
}
