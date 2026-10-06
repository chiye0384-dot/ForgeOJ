/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.classroom;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ClassroomMapper {
    record Room(String id,long ownerId,String title,String status,String inviteSha256,boolean inviteEnabled,long version) {}
    record Member(long userId,String username,String role,String status) {}
    record Summary(String id,String title,String status,long version,String role,String memberStatus) {}
    record Transfer(String id,String classroomId,long fromUserId,long targetUserId,String clientRequestId,String status) {}
    record Creation(String classroomId,String title) {}
    @Select("SELECT id,owner_id,title,status,invite_sha256,invite_enabled,version FROM classroom WHERE id=#{id} FOR UPDATE") Optional<Room> lock(String id);
    @Select("SELECT id,owner_id,title,status,invite_sha256,invite_enabled,version FROM classroom WHERE id=#{id}") Optional<Room> room(String id);
    @Select("SELECT user_id,NULL username,role,status FROM classroom_member WHERE classroom_id=#{id} AND user_id=#{user} FOR UPDATE") Optional<Member> member(@Param("id") String id,@Param("user") long user);
    @Select("SELECT user_id,username,role,m.status FROM classroom_member m JOIN user_account a ON a.id=m.user_id WHERE classroom_id=#{id} AND (#{history} OR m.status='ACTIVE') ORDER BY joined_at,user_id LIMIT 1000") List<Member> members(@Param("id") String id,@Param("history") boolean history);
    @Select("SELECT COUNT(*) FROM classroom_member WHERE classroom_id=#{id}") int memberCount(String id);
    @Select("SELECT COUNT(*) FROM classroom WHERE owner_id=#{user}") int owned(long user);
    @Select("SELECT COUNT(*) FROM classroom_member WHERE user_id=#{user}") long count(long user);
    @Select("SELECT c.id,title,c.status,version,role,m.status member_status FROM classroom c JOIN classroom_member m ON m.classroom_id=c.id WHERE user_id=#{user} ORDER BY c.created_at DESC,c.id DESC LIMIT #{size} OFFSET #{offset}") List<Summary> list(@Param("user") long user,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT classroom_id,title FROM classroom_creation_request WHERE user_id=#{user} AND client_request_id=#{request}") Optional<Creation> creation(@Param("user") long user,@Param("request") String request);
    @Insert("INSERT INTO classroom(id,owner_id,title) VALUES(#{id},#{user},#{title})") void create(@Param("id") String id,@Param("user") long user,@Param("title") String title);
    @Insert("INSERT INTO classroom_creation_request(user_id,client_request_id,classroom_id,title) VALUES(#{user},#{request},#{id},#{title})") void creationRequest(@Param("user") long user,@Param("request") String request,@Param("id") String id,@Param("title") String title);
    @Insert("INSERT INTO classroom_member(classroom_id,user_id,role) VALUES(#{id},#{user},#{role})") void insertMember(@Param("id") String id,@Param("user") long user,@Param("role") String role);
    @Update("UPDATE classroom_member SET role=#{role},status=#{status},updated_at=CURRENT_TIMESTAMP(6) WHERE classroom_id=#{id} AND user_id=#{user}") int changeMember(@Param("id") String id,@Param("user") long user,@Param("role") String role,@Param("status") String status);
    @Update("UPDATE classroom SET owner_id=#{ownerId},title=#{title},status=#{status},invite_sha256=#{inviteSha256},invite_enabled=#{inviteEnabled},version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND version=#{version}") int update(Room room);
    @Select("SELECT id FROM classroom WHERE invite_sha256=#{hash}") Optional<String> invite(String hash);
    @Select("SELECT id,classroom_id,from_user_id,target_user_id,client_request_id,status FROM classroom_transfer WHERE classroom_id=#{id} AND status='PENDING'") Optional<Transfer> pending(String id);
    @Select("SELECT id,classroom_id,from_user_id,target_user_id,client_request_id,status FROM classroom_transfer WHERE classroom_id=#{room} AND id=#{id} FOR UPDATE") Optional<Transfer> transfer(@Param("room") String room,@Param("id") String id);
    @Select("SELECT id,classroom_id,from_user_id,target_user_id,client_request_id,status FROM classroom_transfer WHERE from_user_id=#{user} AND client_request_id=#{request}") Optional<Transfer> transferRequest(@Param("user") long user,@Param("request") String request);
    @Insert("INSERT INTO classroom_transfer(id,classroom_id,from_user_id,target_user_id,client_request_id) VALUES(#{id},#{classroomId},#{fromUserId},#{targetUserId},#{clientRequestId})") void insertTransfer(Transfer t);
    @Update("UPDATE classroom_transfer SET status=#{status},closed_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND status='PENDING'") int close(@Param("id") String id,@Param("status") String status);
    @Select("SELECT COUNT(*) FROM classroom_transfer WHERE classroom_id=#{id}") int transferCount(String id);
    @Select("SELECT status='ACTIVE' FROM user_account WHERE id=#{user}") Optional<Boolean> activeAccount(long user);
    @Delete("DELETE FROM classroom_member WHERE classroom_id=#{id}") void deleteMembers(String id);
    @Delete("DELETE FROM classroom WHERE id=#{id}") void delete(String id);
}
