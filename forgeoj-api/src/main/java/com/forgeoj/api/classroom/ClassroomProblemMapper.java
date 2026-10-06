/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.classroom;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ClassroomProblemMapper {
    record Row(long id,String slug,String title,String status,long version,long createdBy,String draftId,
            long draftVersion,String validationJobId,String snapshotId,String solutionPolicy) {}
    record Frozen(String metadataText,String referenceCode,String solutionIdea,String solutionCode,int testCount) {}
    record Solution(String idea,String code) {}
    String COLUMNS="p.id,p.slug,p.title,p.status,c.version,c.created_by AS createdBy,c.draft_id AS draftId,c.draft_version AS draftVersion,c.validation_job_id AS validationJobId,c.snapshot_id AS snapshotId,c.solution_policy AS solutionPolicy";
    String FROM=" FROM classroom_problem c JOIN problem p ON p.id=c.problem_id AND p.classroom_id=c.classroom_id AND p.scope='CLASSROOM' ";
    @Select("SELECT COUNT(*)"+FROM+"WHERE c.classroom_id=#{room}") long count(String room);
    @Select("SELECT "+COLUMNS+FROM+"WHERE c.classroom_id=#{room} ORDER BY c.created_at DESC,p.id DESC LIMIT #{size} OFFSET #{offset}")
    List<Row> list(@Param("room") String room,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT "+COLUMNS+FROM+"WHERE c.classroom_id=#{room} AND p.slug=#{slug} FOR UPDATE")
    Optional<Row> lock(@Param("room") String room,@Param("slug") String slug);
    @Select("SELECT "+COLUMNS+FROM+"WHERE c.created_by=#{user} AND c.client_request_id=#{request}")
    Optional<Row> request(@Param("user") long user,@Param("request") String request);
    @Select("SELECT s.metadata_text AS metadataText,s.reference_code AS referenceCode,s.solution_idea AS solutionIdea,s.solution_code AS solutionCode,(SELECT COUNT(*) FROM content_validation_test_case t WHERE t.snapshot_id=s.id) AS testCount FROM content_validation_snapshot s WHERE s.id=#{snapshot}")
    Optional<Frozen> frozen(String snapshot);
    @Select("SELECT metadata_text FROM content_validation_snapshot WHERE id=#{snapshot}") String metadata(String snapshot);
    @Select("SELECT solution_idea AS idea,solution_code AS code FROM content_validation_snapshot WHERE id=#{snapshot}") Solution solution(String snapshot);
    @Insert("INSERT INTO problem(slug,title,statement_text,input_description,output_description,public_samples_json,status,scope,classroom_id) VALUES(#{slug},#{title},#{statement},#{input},#{output},CAST(#{samples} AS JSON),'ACTIVE','CLASSROOM',#{room})")
    int problem(@Param("slug") String slug,@Param("room") String room,@Param("title") String title,@Param("statement") String statement,@Param("input") String input,@Param("output") String output,@Param("samples") String samples);
    @Select("SELECT id FROM problem WHERE slug=#{slug} AND classroom_id=#{room} AND scope='CLASSROOM'")
    long identity(@Param("slug") String slug,@Param("room") String room);
    @Insert("INSERT INTO problem_judge_version(problem_id,version_no,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256) SELECT #{problem},1,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256 FROM content_validation_snapshot WHERE id=#{snapshot} AND execution_kind='VALIDATE'")
    int judge(@Param("problem") long problem,@Param("snapshot") String snapshot);
    @Select("SELECT id FROM problem_judge_version WHERE problem_id=#{problem} AND version_no=1") long judgeId(long problem);
    @Insert("INSERT INTO problem_test_case(judge_version_id,ordinal,input_data_gzip,expected_output_gzip,input_size_bytes,output_size_bytes,input_sha256,output_sha256) SELECT #{judge},sequence_no,input_gzip,expected_output_gzip,input_bytes,expected_output_bytes,input_sha256,expected_output_sha256 FROM content_validation_test_case WHERE snapshot_id=#{snapshot} ORDER BY sequence_no")
    int tests(@Param("judge") long judge,@Param("snapshot") String snapshot);
    @Update("UPDATE problem SET current_judge_version_id=#{judge} WHERE id=#{problem} AND scope='CLASSROOM' AND current_judge_version_id IS NULL")
    int activate(@Param("problem") long problem,@Param("judge") long judge);
    @Insert("INSERT INTO classroom_problem(problem_id,classroom_id,created_by,draft_id,draft_version,validation_job_id,snapshot_id,client_request_id,solution_policy) VALUES(#{problem},#{room},#{user},#{draft},#{version},#{job},#{snapshot},#{request},#{policy})")
    int publish(@Param("problem") long problem,@Param("room") String room,@Param("user") long user,@Param("draft") String draft,@Param("version") long version,@Param("job") String job,@Param("snapshot") String snapshot,@Param("request") String request,@Param("policy") String policy);
    @Update("UPDATE problem SET status='ARCHIVED' WHERE id=#{problem} AND scope='CLASSROOM' AND status='ACTIVE'") int archive(long problem);
    @Update("UPDATE classroom_problem SET version=version+1 WHERE problem_id=#{problem} AND version=#{version}")
    int increment(@Param("problem") long problem,@Param("version") long version);
    @Select("SELECT EXISTS(SELECT 1 FROM submission s JOIN problem p ON p.id=s.problem_id WHERE s.user_id=#{user} AND p.id=#{problem} AND s.judge_version_id=p.current_judge_version_id AND s.processing_status='FINISHED' AND s.verdict='AC')")
    boolean completed(@Param("user") long user,@Param("problem") long problem);
    @Insert("INSERT INTO authored_problem_test_case(draft_id,sequence_no,input_gzip,expected_output_gzip,input_bytes,expected_output_bytes,input_sha256,expected_output_sha256) SELECT #{draft},sequence_no,input_gzip,expected_output_gzip,input_bytes,expected_output_bytes,input_sha256,expected_output_sha256 FROM content_validation_test_case WHERE snapshot_id=#{snapshot} ORDER BY sequence_no")
    int copyDraftTests(@Param("draft") String draft,@Param("snapshot") String snapshot);
    @Select("SELECT EXISTS(SELECT 1 FROM classroom_solution_early_view WHERE user_id=#{user} AND problem_id=#{problem})")
    boolean viewed(@Param("user") long user,@Param("problem") long problem);
    @Insert("INSERT IGNORE INTO classroom_solution_early_view(user_id,problem_id) VALUES(#{user},#{problem})")
    int earlyView(@Param("user") long user,@Param("problem") long problem);
}
