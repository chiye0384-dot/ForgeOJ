/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.solution;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SolutionMapper {
    @Select("<script>SELECT p.id AS problemId,j.id AS judgeVersionId,j.version_no AS judgeVersion FROM problem p JOIN problem_judge_version j ON j.id=p.current_judge_version_id AND j.problem_id=p.id WHERE p.slug=#{slug} AND p.status='ACTIVE'<if test='locking'> FOR SHARE</if></script>")
    Optional<Version> version(@Param("slug") String slug,@Param("locking") boolean locking);
    @Select("SELECT EXISTS(SELECT 1 FROM official_problem_solution WHERE judge_version_id=#{versionId})")
    boolean available(long versionId);
    @Select("SELECT EXISTS(SELECT 1 FROM submission WHERE user_id=#{userId} AND problem_id=#{problemId} AND judge_version_id=#{versionId} AND processing_status='FINISHED' AND verdict='AC')")
    boolean completed(@Param("userId") long userId,@Param("problemId") long problemId,@Param("versionId") long versionId);
    @Select("SELECT EXISTS(SELECT 1 FROM user_solution_early_view WHERE user_id=#{userId} AND judge_version_id=#{versionId})")
    boolean viewed(@Param("userId") long userId,@Param("versionId") long versionId);
    @Select("SELECT idea,language,source_code AS sourceCode FROM official_problem_solution WHERE judge_version_id=#{versionId}")
    Optional<Solution> content(long versionId);
    @Insert("INSERT IGNORE INTO user_solution_early_view(user_id,judge_version_id) VALUES(#{userId},#{versionId})")
    int record(@Param("userId") long userId,@Param("versionId") long versionId);
    record Version(long problemId,long judgeVersionId,int judgeVersion) {}
    record Solution(String idea,String language,String sourceCode) {}
}
