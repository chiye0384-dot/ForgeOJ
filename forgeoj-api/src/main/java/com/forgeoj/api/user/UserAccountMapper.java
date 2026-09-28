package com.forgeoj.api.user;

import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserAccountMapper {

    @Select(
            """
            SELECT id,
                   username,
                   password_hash AS passwordHash,
                   status
            FROM user_account
            WHERE username = #{username}
            LIMIT 1
            """)
    Optional<UserAccountCredentials> findByUsername(@Param("username") String username);
}
