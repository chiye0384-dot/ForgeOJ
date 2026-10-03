/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import static com.forgeoj.api.content.ContentRecords.*;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ContentMapper {
    record Row(String id,String title,String metadata,String referenceCode,String solutionIdea,String solutionCode,long version,String status) {}
    record TestRow(int sequence,byte[] inputGzip,byte[] outputGzip,long inputBytes,long outputBytes,String inputSha256,String outputSha256) {}
    String COLUMNS="id,title,CAST(metadata_json AS CHAR CHARACTER SET utf8mb4) AS metadata,reference_code,solution_idea,solution_code,version,status";
    @Select("SELECT COUNT(*) FROM authored_problem_draft WHERE owner_id=#{owner}")
    long count(long owner);
    @Select("SELECT d.id,d.title,d.version,d.status,(SELECT COUNT(*) FROM authored_problem_test_case t WHERE t.draft_id=d.id) AS testCount FROM authored_problem_draft d WHERE d.owner_id=#{owner} ORDER BY d.created_at DESC,d.id DESC LIMIT #{size} OFFSET #{offset}")
    List<Summary> summaries(@Param("owner") long owner,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT "+COLUMNS+" FROM authored_problem_draft WHERE id=#{id} AND owner_id=#{owner}")
    Optional<Row> find(@Param("owner") long owner,@Param("id") String id);
    @Select("SELECT "+COLUMNS+" FROM authored_problem_draft WHERE id=#{id} AND owner_id=#{owner} FOR UPDATE")
    Optional<Row> lock(@Param("owner") long owner,@Param("id") String id);
    @Insert("INSERT INTO authored_problem_draft(id,owner_id,title,metadata_json,reference_code,solution_idea,solution_code) VALUES(#{id},#{owner},#{c.metadata.title},#{metadata},#{c.referenceCode},#{c.solutionIdea},#{c.solutionCode})")
    int insert(@Param("owner") long owner,@Param("id") String id,@Param("c") Content c,@Param("metadata") String metadata);
    @Update("UPDATE authored_problem_draft SET title=#{c.metadata.title},metadata_json=#{metadata},reference_code=#{c.referenceCode},solution_idea=#{c.solutionIdea},solution_code=#{c.solutionCode},version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND owner_id=#{owner} AND version=#{version} AND status='DRAFT'")
    int save(@Param("owner") long owner,@Param("id") String id,@Param("version") long version,@Param("c") Content c,@Param("metadata") String metadata);
    @Update("UPDATE authored_problem_draft SET version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND owner_id=#{owner} AND version=#{version} AND status='DRAFT'")
    int increment(@Param("owner") long owner,@Param("id") String id,@Param("version") long version);
    @Update("UPDATE authored_problem_draft SET status='ARCHIVED',version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND owner_id=#{owner} AND version=#{version} AND status='DRAFT'")
    int archive(@Param("owner") long owner,@Param("id") String id,@Param("version") long version);
    @Select("SELECT COUNT(*) FROM authored_problem_test_case t JOIN authored_problem_draft d ON d.id=t.draft_id WHERE d.id=#{id} AND d.owner_id=#{owner}")
    int testCount(@Param("owner") long owner,@Param("id") String id);
    @Select("SELECT t.sequence_no AS sequence,t.input_gzip AS inputGzip,t.expected_output_gzip AS outputGzip,t.input_bytes AS inputBytes,t.expected_output_bytes AS outputBytes,t.input_sha256 AS inputSha256,t.expected_output_sha256 AS outputSha256 FROM authored_problem_test_case t JOIN authored_problem_draft d ON d.id=t.draft_id WHERE d.id=#{id} AND d.owner_id=#{owner} ORDER BY t.sequence_no")
    List<TestRow> tests(@Param("owner") long owner,@Param("id") String id);
    @Delete("DELETE t FROM authored_problem_test_case t JOIN authored_problem_draft d ON d.id=t.draft_id WHERE d.id=#{id} AND d.owner_id=#{owner}")
    int clearTests(@Param("owner") long owner,@Param("id") String id);
    @Insert("INSERT INTO authored_problem_test_case(draft_id,sequence_no,input_gzip,expected_output_gzip,input_bytes,expected_output_bytes,input_sha256,expected_output_sha256) SELECT d.id,#{t.sequence},#{t.inputGzip},#{t.outputGzip},#{t.inputBytes},#{t.outputBytes},#{t.inputSha256},#{t.outputSha256} FROM authored_problem_draft d WHERE d.id=#{id} AND d.owner_id=#{owner} AND d.status='DRAFT'")
    int insertTest(@Param("owner") long owner,@Param("id") String id,@Param("t") TestRow t);
    @Delete("DELETE FROM authored_problem_draft WHERE id=#{id} AND owner_id=#{owner} AND version=#{version} AND status='DRAFT'")
    int delete(@Param("owner") long owner,@Param("id") String id,@Param("version") long version);
}
