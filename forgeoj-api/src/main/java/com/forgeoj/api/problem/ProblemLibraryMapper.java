/*
 * Copyright 2026 池也
 * SPDX-License-Identifier: Apache-2.0
 */
package com.forgeoj.api.problem;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProblemLibraryMapper {

    String FILTERED_PROBLEMS = """
            FROM problem p
            JOIN problem_judge_version jv ON jv.id = p.current_judge_version_id
            WHERE p.status = 'ACTIVE' AND p.scope = 'PUBLIC'
            <if test="pattern != null">AND p.title LIKE #{pattern} ESCAPE '='</if>
            <if test="difficulty != null">AND p.difficulty = #{difficulty}</if>
            <if test="tag != null">
                AND EXISTS (SELECT 1 FROM problem_tag pt WHERE pt.problem_id = p.id AND pt.tag = #{tag})
            </if>
            """;

    @Select("<script>SELECT COUNT(*) " + FILTERED_PROBLEMS + "</script>")
    long count(@Param("pattern") String pattern, @Param("difficulty") String difficulty,
            @Param("tag") String tag);

    @Select("<script>SELECT p.id, p.slug, p.title, p.difficulty, jv.version_no AS judgeVersion "
            + FILTERED_PROBLEMS + " ORDER BY p.id ASC LIMIT #{size} OFFSET #{offset}</script>")
    List<ProblemSummaryRow> page(@Param("pattern") String pattern,
            @Param("difficulty") String difficulty, @Param("tag") String tag,
            @Param("size") int size, @Param("offset") long offset);

    @Select("""
            <script>
            SELECT problem_id AS problemId, tag
            FROM problem_tag
            WHERE problem_id IN
                <foreach collection="problemIds" item="problemId" open="(" separator="," close=")">
                    #{problemId}
                </foreach>
            ORDER BY problem_id ASC, tag ASC
            </script>
            """)
    List<ProblemTagRow> tagsForProblems(@Param("problemIds") List<Long> problemIds);

    @Select("""
            SELECT DISTINCT pt.tag
            FROM problem_tag pt
            JOIN problem p ON p.id = pt.problem_id
            JOIN problem_judge_version jv ON jv.id = p.current_judge_version_id
            WHERE p.status = 'ACTIVE' AND p.scope = 'PUBLIC'
            ORDER BY pt.tag ASC
            """)
    List<String> availableTags();

    record ProblemSummaryRow(long id, String slug, String title, String difficulty, int judgeVersion) {}

    record ProblemTagRow(long problemId, String tag) {}
}
