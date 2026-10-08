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
                   jv.output_limit_bytes AS outputLimitBytes,
                   CAST(p.public_samples_json AS CHAR CHARACTER SET utf8mb4) AS publicSamplesJson,
                   u.username AS authorName,
                   JSON_UNQUOTE(JSON_EXTRACT(s.metadata_text,'$.originType')) AS originType,
                   NULLIF(JSON_UNQUOTE(JSON_EXTRACT(s.metadata_text,'$.sourceUrl')),'null') AS sourceUrl,
                   JSON_UNQUOTE(JSON_EXTRACT(s.metadata_text,'$.licenseStatement')) AS licenseStatement,
                   corrected.slug AS correctionOfSlug
            FROM problem p
            JOIN problem_judge_version jv ON jv.id = p.current_judge_version_id
            LEFT JOIN public_problem_governance g ON g.problem_id=p.id
            LEFT JOIN content_validation_snapshot s ON s.id=g.snapshot_id
            LEFT JOIN user_account u ON u.id=g.author_id
            LEFT JOIN problem corrected ON corrected.id=g.correction_of_id
            WHERE p.slug = #{slug}
              AND p.status = 'ACTIVE' AND p.scope = 'PUBLIC'
            LIMIT 1
            """)
    Optional<ProblemDetailsRow> findActiveBySlug(@Param("slug") String slug);
}
