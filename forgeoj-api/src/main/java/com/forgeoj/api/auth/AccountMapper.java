package com.forgeoj.api.auth;

import java.time.LocalDateTime;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AccountMapper {
    record Account(long id, String username, String passwordHash, String status, String email, LocalDateTime emailVerifiedAt) {}
    record Session(String id, long userId, LocalDateTime expiresAt, LocalDateTime revokedAt) {}
    record Refresh(String tokenSha256, String sessionId, LocalDateTime consumedAt) {}
    record Action(String tokenSha256, long userId, String purpose, String targetEmail, LocalDateTime expiresAt, LocalDateTime consumedAt) {}

    @Select("SELECT id, username, password_hash, status, email, email_verified_at FROM user_account WHERE username = #{identifier} OR email = #{identifier} LIMIT 1")
    Optional<Account> findAccount(String identifier);
    @Select("SELECT id, username, password_hash, status, email, email_verified_at FROM user_account WHERE id = #{id} FOR UPDATE")
    Optional<Account> lockAccount(long id);
    @Insert("INSERT INTO user_account(username, email, password_hash, nickname, status) VALUES(#{username},#{email},#{hash},#{nickname},'PENDING_VERIFICATION')")
    void insertAccount(@Param("username") String username, @Param("email") String email, @Param("hash") String hash, @Param("nickname") String nickname);
    @Select("SELECT LAST_INSERT_ID()") long insertedId();
    @Insert("INSERT INTO user_judge_quota_lock(user_id) VALUES(#{id})") void insertQuota(long id);
    @Update("UPDATE user_account SET status='ACTIVE', email_verified_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND status='PENDING_VERIFICATION'") int activate(long id);
    @Update("UPDATE user_account SET email=#{email}, email_verified_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND status='ACTIVE'")
    int bindEmail(@Param("id") long id, @Param("email") String email);
    @Update("UPDATE user_account SET password_hash=#{hash} WHERE id=#{id}")
    void password(@Param("id") long id, @Param("hash") String hash);
    @Update("UPDATE user_account SET status='DISABLED' WHERE id=#{id}") void disable(long id);

    @Insert("INSERT INTO login_session(id,user_id,expires_at) VALUES(#{id},#{userId},#{expiresAt})") void insertSession(Session session);
    @Select("SELECT id,user_id,expires_at,revoked_at FROM login_session WHERE id=#{id}") Optional<Session> session(String id);
    @Select("SELECT id,user_id,expires_at,revoked_at FROM login_session WHERE id=#{id} FOR UPDATE") Optional<Session> lockSession(String id);
    @Select("SELECT u.id,u.username,u.password_hash,u.status,u.email,u.email_verified_at FROM user_account u JOIN login_session s ON s.user_id=u.id WHERE s.id=#{sid} AND u.status='ACTIVE' AND s.revoked_at IS NULL AND s.expires_at>CURRENT_TIMESTAMP(6)")
    Optional<Account> authenticated(String sid);
    @Update("UPDATE login_session SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP(6)) WHERE id=#{sid}") void revoke(String sid);
    @Update("UPDATE login_session SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP(6)) WHERE user_id=#{id}") void revokeAll(long id);
    @Insert("INSERT INTO refresh_token(token_sha256,session_id) VALUES(#{hash},#{sid})")
    void insertRefresh(@Param("hash") String hash, @Param("sid") String sid);
    @Select("SELECT token_sha256,session_id,consumed_at FROM refresh_token WHERE token_sha256=#{hash}") Optional<Refresh> refresh(String hash);
    @Select("SELECT token_sha256,session_id,consumed_at FROM refresh_token WHERE token_sha256=#{hash} FOR UPDATE") Optional<Refresh> lockRefresh(String hash);
    @Update("UPDATE refresh_token SET consumed_at=CURRENT_TIMESTAMP(6) WHERE token_sha256=#{hash} AND consumed_at IS NULL") int consumeRefresh(String hash);

    @Insert("INSERT INTO account_action_token(token_sha256,user_id,purpose,target_email,expires_at) VALUES(#{tokenSha256},#{userId},#{purpose},#{targetEmail},#{expiresAt})") void insertAction(Action action);
    @Select("SELECT token_sha256,user_id,purpose,target_email,expires_at,consumed_at FROM account_action_token WHERE token_sha256=#{hash}") Optional<Action> action(String hash);
    @Select("SELECT token_sha256,user_id,purpose,target_email,expires_at,consumed_at FROM account_action_token WHERE token_sha256=#{hash} FOR UPDATE") Optional<Action> lockAction(String hash);
    @Select("SELECT COUNT(*) FROM account_action_token WHERE user_id=#{id} AND purpose=#{purpose} AND created_at>DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL #{seconds} SECOND)")
    int countActions(@Param("id") long id, @Param("purpose") String purpose, @Param("seconds") int seconds);
    @Update("UPDATE account_action_token SET consumed_at=CURRENT_TIMESTAMP(6) WHERE user_id=#{id} AND purpose=#{purpose} AND consumed_at IS NULL")
    void invalidateActions(@Param("id") long id, @Param("purpose") String purpose);
    @Update("UPDATE account_action_token SET consumed_at=CURRENT_TIMESTAMP(6) WHERE token_sha256=#{hash} AND consumed_at IS NULL") int consumeAction(String hash);
}
