/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PublicFeedbackMapper {
    record Case(String id,long problemId,String slug,String title,String status,long version,String resolution,long reports) {}
    record Feedback(String id,String caseId,long userId,String category,String body,String caseStatus,String resolution) {}
    @Select("SELECT id FROM problem WHERE slug=#{slug} AND scope='PUBLIC' AND status='ACTIVE' FOR UPDATE") Optional<Long> lockPublic(String slug);
    String CASE="c.id,c.problem_id,p.slug,p.title,c.status,c.version,c.resolution,(SELECT COUNT(*) FROM public_problem_feedback f WHERE f.case_id=c.id) AS reports";
    String FROM=" FROM public_problem_feedback_case c JOIN problem p ON p.id=c.problem_id AND p.scope='PUBLIC' ";
    @Select("SELECT "+CASE+FROM+"WHERE c.id=#{id}") Optional<Case> find(String id);
    @Select("SELECT "+CASE+FROM+"WHERE c.id=#{id} FOR UPDATE") Optional<Case> lock(String id);
    @Select("SELECT id FROM public_problem_feedback_case WHERE problem_id=#{problem} AND status='OPEN' FOR UPDATE") Optional<String> open(long problem);
    @Select("SELECT COUNT(*) FROM public_problem_feedback_case WHERE problem_id=#{problem}") long caseCount(long problem);
    @Insert("INSERT INTO public_problem_feedback_case(id,problem_id,created_at) VALUES(#{id},#{problem},UTC_TIMESTAMP(6))") int create(@Param("id")String id,@Param("problem")long problem);
    String FEEDBACK="f.id,f.case_id,f.user_id,f.category,f.body,c.status AS case_status,c.resolution";
    @Select("SELECT "+FEEDBACK+" FROM public_problem_feedback f JOIN public_problem_feedback_case c ON c.id=f.case_id JOIN problem p ON p.id=c.problem_id AND p.scope='PUBLIC' WHERE f.user_id=#{user} AND f.client_request_id=#{request} AND p.slug=#{slug}")
    Optional<Feedback> request(@Param("user")long user,@Param("request")String request,@Param("slug")String slug);
    @Select("SELECT EXISTS(SELECT 1 FROM public_problem_feedback WHERE user_id=#{user} AND client_request_id=#{request})") boolean used(@Param("user")long user,@Param("request")String request);
    @Select("SELECT "+FEEDBACK+" FROM public_problem_feedback f JOIN public_problem_feedback_case c ON c.id=f.case_id WHERE f.case_id=#{caseId} AND f.user_id=#{user}")
    Optional<Feedback> existing(@Param("caseId")String caseId,@Param("user")long user);
    @Insert("INSERT INTO public_problem_feedback(id,case_id,user_id,client_request_id,category,body,created_at) VALUES(#{id},#{caseId},#{user},#{request},#{category},#{body},UTC_TIMESTAMP(6))")
    int report(@Param("id")String id,@Param("caseId")String caseId,@Param("user")long user,@Param("request")String request,@Param("category")String category,@Param("body")String body);
    @Select("SELECT "+FEEDBACK+" FROM public_problem_feedback f JOIN public_problem_feedback_case c ON c.id=f.case_id JOIN problem p ON p.id=c.problem_id AND p.scope='PUBLIC' WHERE f.user_id=#{user} AND p.slug=#{slug} ORDER BY f.created_at DESC,f.id DESC LIMIT #{size} OFFSET #{offset}")
    List<Feedback> mine(@Param("user")long user,@Param("slug")String slug,@Param("size")int size,@Param("offset")long offset);
    @Select("SELECT COUNT(*) FROM public_problem_feedback f JOIN public_problem_feedback_case c ON c.id=f.case_id JOIN problem p ON p.id=c.problem_id AND p.scope='PUBLIC' WHERE f.user_id=#{user} AND p.slug=#{slug}") long mineCount(@Param("user")long user,@Param("slug")String slug);
    @Select("<script>SELECT COUNT(*)"+FROM+"<if test='status!=null'>WHERE c.status=#{status}</if></script>") long count(String status);
    @Select("<script>SELECT "+CASE+FROM+"<if test='status!=null'>WHERE c.status=#{status}</if> ORDER BY c.created_at DESC,c.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<Case> cases(@Param("status")String status,@Param("size")int size,@Param("offset")long offset);
    @Select("SELECT "+FEEDBACK+" FROM public_problem_feedback f JOIN public_problem_feedback_case c ON c.id=f.case_id WHERE c.id=#{id} ORDER BY f.created_at,f.id LIMIT #{size} OFFSET #{offset}")
    List<Feedback> reports(@Param("id")String id,@Param("size")int size,@Param("offset")long offset);
    @Update("UPDATE public_problem_feedback_case SET status='CLOSED',version=version+1,resolution=#{reason},resolved_by=#{actor},closed_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND status='OPEN' AND version=#{version}")
    int close(@Param("id")String id,@Param("version")long version,@Param("reason")String reason,@Param("actor")long actor);
}
