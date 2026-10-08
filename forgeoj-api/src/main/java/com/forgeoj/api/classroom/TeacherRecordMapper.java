/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.classroom;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
interface TeacherRecordMapper {
    record Participant(long userId,String username,String memberStatus,String role) {}
    record GradeRow(long userId,int ordinal,String slug,String title,long judgeVersionId,int attempts,boolean precompleted,LocalDateTime acceptedAt,LocalDateTime firstAcAt,boolean dataInvalid) {}
    record Attempt(String submissionId,long userId,int ordinal,String problemSlug,long judgeVersionId,String language,String processingStatus,String verdict,LocalDateTime acceptedAt,LocalDateTime finishedAt) {}
    record Source(String submissionId,long userId,int ordinal,String problemSlug,long judgeVersionId,String language,String processingStatus,String verdict,LocalDateTime acceptedAt,LocalDateTime finishedAt,String sourceCode,String sourceSha256) {}
    @Select("SELECT COUNT(*) FROM assignment_participant WHERE assignment_id=#{id}") long participantCount(String id);
    @Select("SELECT p.user_id,u.username,m.status member_status,m.role FROM assignment_participant p JOIN user_account u ON u.id=p.user_id JOIN classroom_member m ON m.classroom_id=p.classroom_id AND m.user_id=p.user_id WHERE p.assignment_id=#{id} ORDER BY p.user_id LIMIT #{size} OFFSET #{offset}")
    List<Participant> participants(@Param("id") String id,@Param("size") int size,@Param("offset") long offset);
    // Filter before windowing. This query contains no source or private PRECOMPLETED submission ID.
    @Select("""
        <script>
        WITH scoped AS (
          SELECT a.user_id,a.problem_id,a.submission_id,a.accepted_at,s.processing_status,s.verdict,s.finished_at
          FROM assignment_attempt a JOIN submission s ON s.id=a.submission_id AND s.user_id=a.user_id
            AND s.problem_id=a.problem_id AND s.judge_version_id=a.judge_version_id
          WHERE a.assignment_id=#{id} AND a.user_id IN
          <foreach collection="users" item="u" open="(" separator="," close=")">#{u}</foreach>
        ), counts AS (SELECT user_id,problem_id,COUNT(*) attempts FROM scoped GROUP BY user_id,problem_id),
        ac AS (
          SELECT *,ROW_NUMBER() OVER(PARTITION BY user_id,problem_id ORDER BY accepted_at,submission_id) accepted_rank,
            ROW_NUMBER() OVER(PARTITION BY user_id,problem_id ORDER BY finished_at,submission_id) finished_rank
          FROM scoped WHERE processing_status='FINISHED' AND verdict='AC'
        )
        SELECT m.user_id,p.ordinal,p.problem_slug slug,JSON_UNQUOTE(JSON_EXTRACT(p.metadata_text,'$.title')) title,
          p.judge_version_id,COALESCE(c.attempts,0) attempts,(pre.submission_id IS NOT NULL) precompleted,
          a.accepted_at,TIMESTAMPADD(SECOND,-TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),f.finished_at) first_ac_at,
          EXISTS(SELECT 1 FROM public_problem_governance g WHERE g.problem_id=p.problem_id AND g.data_invalid=TRUE) data_invalid
        FROM assignment_participant m JOIN assignment_problem p ON p.assignment_id=m.assignment_id
        LEFT JOIN counts c ON c.user_id=m.user_id AND c.problem_id=p.problem_id
        LEFT JOIN ac a ON a.user_id=m.user_id AND a.problem_id=p.problem_id AND a.accepted_rank=1
        LEFT JOIN ac f ON f.user_id=m.user_id AND f.problem_id=p.problem_id AND f.finished_rank=1
        LEFT JOIN assignment_precompletion pre ON pre.assignment_id=m.assignment_id AND pre.user_id=m.user_id AND pre.problem_id=p.problem_id
        WHERE m.assignment_id=#{id} AND m.user_id IN
        <foreach collection="users" item="u" open="(" separator="," close=")">#{u}</foreach>
        ORDER BY m.user_id,p.ordinal
        </script>
        """)
    List<GradeRow> grades(@Param("id") String id,@Param("users") List<Long> users);
    @Select("SELECT EXISTS(SELECT 1 FROM assignment_participant m JOIN assignment_problem p ON p.assignment_id=m.assignment_id WHERE m.assignment_id=#{id} AND m.user_id=#{user} AND p.ordinal=#{ordinal})")
    boolean scope(@Param("id") String id,@Param("user") long user,@Param("ordinal") int ordinal);
    String JOINS=" FROM assignment_attempt a JOIN assignment_problem p ON p.assignment_id=a.assignment_id AND p.problem_id=a.problem_id AND p.judge_version_id=a.judge_version_id JOIN submission s ON s.id=a.submission_id AND s.user_id=a.user_id AND s.problem_id=a.problem_id AND s.judge_version_id=a.judge_version_id ";
    String COLUMNS="s.id submission_id,s.user_id,p.ordinal,p.problem_slug,s.judge_version_id,s.language,s.processing_status,s.verdict,a.accepted_at,TIMESTAMPADD(SECOND,-TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),s.finished_at) finished_at";
    @Select("SELECT COUNT(*)"+JOINS+"WHERE a.assignment_id=#{id} AND a.user_id=#{user} AND p.ordinal=#{ordinal}")
    long attemptCount(@Param("id") String id,@Param("user") long user,@Param("ordinal") int ordinal);
    @Select("SELECT "+COLUMNS+JOINS+"WHERE a.assignment_id=#{id} AND a.user_id=#{user} AND p.ordinal=#{ordinal} ORDER BY a.accepted_at DESC,a.submission_id DESC LIMIT #{size} OFFSET #{offset}")
    List<Attempt> attempts(@Param("id") String id,@Param("user") long user,@Param("ordinal") int ordinal,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT "+COLUMNS+",s.source_code,s.source_sha256"+JOINS+"WHERE a.assignment_id=#{id} AND a.submission_id=#{submission} LIMIT 1")
    Optional<Source> source(@Param("id") String id,@Param("submission") String submission);
}
