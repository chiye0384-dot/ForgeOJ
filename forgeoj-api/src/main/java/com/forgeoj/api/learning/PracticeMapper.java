/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.learning;

import java.util.*;
import org.apache.ibatis.annotations.*;
import com.forgeoj.api.problem.ProblemLibraryMapper;

@Mapper
public interface PracticeMapper {
    String PROGRESS = "CASE WHEN NOT EXISTS(SELECT 1 FROM public_problem_governance g WHERE g.problem_id=p.id AND g.data_invalid=TRUE) AND "
        + LearningMapper.AC + " THEN 'SOLVED' WHEN EXISTS(SELECT 1 FROM submission a WHERE a.user_id=#{userId} AND a.problem_id=p.id AND a.judge_version_id=p.current_judge_version_id) THEN 'ATTEMPTED' ELSE 'UNTRIED' END";
    String FILTER = ProblemLibraryMapper.FILTERED_PROBLEMS + "<if test='progress != null'> AND ("+PROGRESS+")=#{progress}</if>";
    @Select("<script>SELECT COUNT(*) "+FILTER+"</script>")
    long count(@Param("userId") long userId,@Param("pattern") String pattern,@Param("difficulty") String difficulty,@Param("tag") String tag,@Param("progress") String progress);
    @Select("<script>SELECT p.id,p.slug,p.title,p.difficulty,jv.version_no AS judgeVersion "+FILTER+" ORDER BY p.id LIMIT #{size} OFFSET #{offset}</script>")
    List<ProblemLibraryMapper.ProblemSummaryRow> page(@Param("userId") long userId,@Param("pattern") String pattern,@Param("difficulty") String difficulty,@Param("tag") String tag,@Param("progress") String progress,@Param("size") int size,@Param("offset") long offset);
    @Select("<script>SELECT p.slug,"+PROGRESS+" AS progress FROM problem p WHERE p.status='ACTIVE' AND p.scope='PUBLIC' AND p.current_judge_version_id IS NOT NULL AND p.slug IN <foreach collection='slugs' item='slug' open='(' separator=',' close=')'>#{slug}</foreach></script>")
    List<State> states(@Param("userId") long userId,@Param("slugs") List<String> slugs);
    @Select("SELECT p.slug,p.title FROM user_code_draft d JOIN problem p ON p.id=d.problem_id WHERE d.user_id=#{userId} AND p.scope='PUBLIC' AND p.status='ACTIVE' AND p.current_judge_version_id IS NOT NULL ORDER BY d.updated_at DESC,p.id LIMIT 1")
    Optional<Resume> resume(long userId);
    @Select("SELECT s.id AS submissionId,s.language,s.source_code AS sourceCode,jv.version_no AS judgeVersion,s.created_at AS createdAt,CASE WHEN p.status='ACTIVE' AND p.current_judge_version_id IS NOT NULL THEN p.slug END AS problemSlug FROM submission s JOIN problem p ON p.id=s.problem_id JOIN problem_judge_version jv ON jv.id=s.judge_version_id WHERE s.id=#{id} AND s.user_id=#{userId} AND p.scope='PUBLIC'")
    Optional<Source> source(@Param("userId") long userId,@Param("id") String id);
    record State(String slug,String progress) {}
    record Resume(String slug,String title) {}
    record Source(String submissionId,String language,String sourceCode,int judgeVersion,java.time.LocalDateTime createdAt,String problemSlug) {}
}
