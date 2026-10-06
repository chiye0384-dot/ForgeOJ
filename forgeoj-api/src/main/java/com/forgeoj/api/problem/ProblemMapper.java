package com.forgeoj.api.problem;

import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProblemMapper {

    @Select(
            """
            SELECT p.slug,
                   p.title,
                   p.statement_text AS statementText,
                   p.input_description AS inputDescription,
                   p.output_description AS outputDescription,
                   JSON_UNQUOTE(JSON_EXTRACT(p.public_samples_json, '$[0].input')) AS sampleInput,
                   JSON_UNQUOTE(JSON_EXTRACT(p.public_samples_json, '$[0].output')) AS sampleOutput,
                   jv.version_no AS judgeVersion,
                   jv.time_limit_ms AS timeLimitMs,
                   jv.memory_limit_mb AS memoryLimitMb,
                   jv.output_limit_bytes AS outputLimitBytes
            FROM problem p
            JOIN problem_judge_version jv ON jv.id = p.current_judge_version_id
            WHERE p.slug = #{slug}
              AND p.status = 'ACTIVE' AND p.scope = 'PUBLIC'
            LIMIT 1
            """)
    Optional<ProblemDetailsRow> findActiveBySlug(@Param("slug") String slug);
}
