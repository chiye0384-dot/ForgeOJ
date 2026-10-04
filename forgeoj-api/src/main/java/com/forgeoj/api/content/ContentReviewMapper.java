/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ContentReviewMapper {
    record Review(String reviewId,String draftId,long draftVersion,long reviewNo,String validationJobId,String status,long version) {}
    record Binding(String snapshotId,long draftVersion,String processingStatus,String validationStatus,String referenceResult,String solutionResult) {}
    record Frozen(String metadataText,String referenceCode,String solutionIdea,String solutionCode,int testCount) {}
    record Detail(Review review,ContentRecords.Content content,int testCount) {}
    record Page(List<Review> items,int page,int size,long total) {}
    String COLUMNS="id AS reviewId,draft_id AS draftId,draft_version AS draftVersion,review_no AS reviewNo,validation_job_id AS validationJobId,review_status AS status,version";
    @Select("SELECT "+COLUMNS+" FROM content_review WHERE owner_id=#{owner} AND request_id=#{request} FOR UPDATE")
    Optional<Review> request(@Param("owner") long owner,@Param("request") String request);
    @Select("SELECT "+COLUMNS+" FROM content_review WHERE owner_id=#{owner} AND draft_id=#{draft} AND id=#{review}")
    Optional<Review> find(@Param("owner") long owner,@Param("draft") String draft,@Param("review") String review);
    @Select("SELECT "+COLUMNS+" FROM content_review WHERE owner_id=#{owner} AND draft_id=#{draft} AND id=#{review} FOR UPDATE")
    Optional<Review> lock(@Param("owner") long owner,@Param("draft") String draft,@Param("review") String review);
    @Select("SELECT j.snapshot_id AS snapshotId,s.draft_version AS draftVersion,j.processing_status AS processingStatus,j.validation_status AS validationStatus,j.reference_result AS referenceResult,j.solution_result AS solutionResult FROM content_validation_job j JOIN content_validation_snapshot s ON s.id=j.snapshot_id WHERE j.owner_id=#{owner} AND s.draft_id=#{draft} AND j.id=#{job}")
    Optional<Binding> validation(@Param("owner") long owner,@Param("draft") String draft,@Param("job") String job);
    @Select("SELECT review_no FROM content_review WHERE draft_id=#{draft} ORDER BY review_no DESC LIMIT 1 FOR UPDATE")
    Optional<Long> lastNumber(String draft);
    @Insert("INSERT INTO content_review(id,draft_id,owner_id,draft_version,validation_job_id,snapshot_id,request_id,review_no) VALUES(#{id},#{draft},#{owner},#{version},#{job},#{snapshot},#{request},#{number})")
    int insert(@Param("id") String id,@Param("draft") String draft,@Param("owner") long owner,@Param("version") long version,@Param("job") String job,@Param("snapshot") String snapshot,@Param("request") String request,@Param("number") long number);
    @Update("UPDATE content_review SET review_status='WITHDRAWN',version=version+1,withdrawn_at=CURRENT_TIMESTAMP(6) WHERE id=#{review} AND owner_id=#{owner} AND draft_id=#{draft} AND review_status='PENDING' AND version=#{version}")
    int withdraw(@Param("owner") long owner,@Param("draft") String draft,@Param("review") String review,@Param("version") long version);
    @Select("SELECT COUNT(*) FROM content_review WHERE owner_id=#{owner} AND draft_id=#{draft}")
    long count(@Param("owner") long owner,@Param("draft") String draft);
    @Select("SELECT "+COLUMNS+" FROM content_review WHERE owner_id=#{owner} AND draft_id=#{draft} ORDER BY review_no DESC LIMIT #{size} OFFSET #{offset}")
    List<Review> list(@Param("owner") long owner,@Param("draft") String draft,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT s.metadata_text AS metadataText,s.reference_code AS referenceCode,s.solution_idea AS solutionIdea,s.solution_code AS solutionCode,(SELECT COUNT(*) FROM content_validation_test_case t WHERE t.snapshot_id=s.id) AS testCount FROM content_review r JOIN content_validation_snapshot s ON s.id=r.snapshot_id WHERE r.owner_id=#{owner} AND r.draft_id=#{draft} AND r.id=#{review}")
    Optional<Frozen> frozen(@Param("owner") long owner,@Param("draft") String draft,@Param("review") String review);
}
