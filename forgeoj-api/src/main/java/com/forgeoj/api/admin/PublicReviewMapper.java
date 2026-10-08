/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** Every query is bound to a review, public problem, or selected feedback case. */
@Mapper
public interface PublicReviewMapper {
    record Review(String id,String draftId,long ownerId,long draftVersion,String snapshotId,String jobId,
            String status,long version,String title,String decisionReason,String publishedSlug) {}
    record Frozen(String metadataText,String solutionIdea,String solutionCode,int testCount,
            String dataset,String image,int timeLimitMs,int memoryLimitMb,long outputLimitBytes,String comparison,String sandbox) {}
    record Decision(String reviewId,long actorAdminId,String requestId,String requestSha256,String decision,String reason,Long problemId,String publishedSlug) {}
    record Problem(long id,String slug,String title,String status,long version,Long authorId,String snapshotId,
            boolean dataInvalid,String invalidReason,String stateReason,Long correctionOfId,long judgeVersionId) {}
    record Revision(String draftId,long problemId,long ownerId,String requestId,long expectedVersion,String revisionKind) {}
    record Recheck(String reviewId,String jobId,String requestSha256) {}
    String REVIEW="r.id,r.draft_id,r.owner_id,r.draft_version,r.snapshot_id,COALESCE(r.latest_recheck_id,r.validation_job_id) AS job_id,r.review_status AS status,r.version,JSON_UNQUOTE(JSON_EXTRACT(s.metadata_text,'$.title')) AS title,d.reason AS decision_reason,p.slug AS published_slug";
    String REVIEW_FROM=" FROM content_review r JOIN content_validation_snapshot s ON s.id=r.snapshot_id LEFT JOIN public_review_decision d ON d.review_id=r.id LEFT JOIN problem p ON p.id=d.problem_id ";
    @Select("<script>SELECT COUNT(*) FROM content_review r <if test='status!=null'>WHERE r.review_status=#{status}</if></script>") long reviewCount(String status);
    @Select("<script>SELECT "+REVIEW+REVIEW_FROM+"<if test='status!=null'>WHERE r.review_status=#{status}</if> ORDER BY r.submitted_at DESC,r.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<Review> reviews(@Param("status")String status,@Param("size")int size,@Param("offset")long offset);
    @Select("SELECT "+REVIEW+REVIEW_FROM+"WHERE r.id=#{review}") Optional<Review> review(String review);
    @Select("SELECT id FROM content_review WHERE id=#{review} FOR UPDATE") Optional<String> lockReviewId(String review);
    // MySQL 8.4 locking joins also require write permission on each joined table.
    // Lock only the mutable case; snapshots and decisions remain insert-only.
    default Optional<Review> lockReview(String review){return lockReviewId(review).flatMap(this::review);}
    @Select("SELECT metadata_text,solution_idea,solution_code,(SELECT COUNT(*) FROM content_validation_test_case t WHERE t.snapshot_id=s.id) AS test_count,test_dataset_sha256 AS dataset,java_image_digest AS image,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version AS comparison,sandbox_policy_version AS sandbox FROM content_validation_snapshot s WHERE id=#{snapshot} AND execution_kind='VALIDATE'") Optional<Frozen> frozen(String snapshot);
    @Select("SELECT s.reference_code FROM content_review r JOIN content_validation_snapshot s ON s.id=r.snapshot_id WHERE r.id=#{review}") String reference(String review);
    @Select("SELECT processing_status='FINISHED' AND validation_status='PASSED' AND reference_result='ACCEPTED' AND solution_result='ACCEPTED' AND snapshot_id=#{snapshot} AND execution_kind='VALIDATE' FROM content_validation_job WHERE id=#{job}")
    Boolean passed(@Param("job")String job,@Param("snapshot")String snapshot);
    @Select("SELECT processing_status FROM content_validation_job WHERE id=#{job}") String jobStatus(String job);
    @Select("SELECT d.review_id,d.actor_admin_id,d.client_request_id AS request_id,d.public_request_sha256 AS request_sha256,d.decision,d.reason,d.problem_id,p.slug AS published_slug FROM public_review_decision d LEFT JOIN problem p ON p.id=d.problem_id WHERE d.actor_admin_id=#{actor} AND d.client_request_id=#{request}")
    Optional<Decision> decision(@Param("actor")long actor,@Param("request")String request);
    @Insert("INSERT INTO public_review_decision(review_id,actor_admin_id,client_request_id,public_request_sha256,decision,reason,problem_id,decided_at) VALUES(#{review},#{actor},#{request},#{hash},#{decision},#{reason},#{problem},UTC_TIMESTAMP(6))")
    int insertDecision(@Param("review")String review,@Param("actor")long actor,@Param("request")String request,@Param("hash")String hash,@Param("decision")String decision,@Param("reason")String reason,@Param("problem")Long problem);
    @Update("UPDATE content_review SET review_status=#{status},version=version+1 WHERE id=#{review} AND review_status='PENDING' AND version=#{version}")
    int finish(@Param("review")String review,@Param("version")long version,@Param("status")String status);
    @Select("SELECT draft_id,problem_id,owner_id,client_request_id AS request_id,expected_version,revision_kind FROM public_revision_draft WHERE draft_id=#{draft}") Optional<Revision> revision(String draft);
    @Select("SELECT draft_id,problem_id,owner_id,client_request_id AS request_id,expected_version,revision_kind FROM public_revision_draft WHERE owner_id=#{owner} AND client_request_id=#{request}")
    Optional<Revision> revisionRequest(@Param("owner")long owner,@Param("request")String request);
    @Insert("INSERT INTO public_revision_draft(draft_id,problem_id,owner_id,client_request_id,expected_version,revision_kind) VALUES(#{draft},#{problem},#{owner},#{request},#{version},#{kind})")
    int insertRevision(@Param("draft")String draft,@Param("problem")long problem,@Param("owner")long owner,@Param("request")String request,@Param("version")long version,@Param("kind")String kind);
    @Insert("INSERT INTO authored_problem_test_case(draft_id,sequence_no,input_gzip,expected_output_gzip,input_bytes,expected_output_bytes,input_sha256,expected_output_sha256) SELECT #{draft},sequence_no,input_gzip,expected_output_gzip,input_bytes,expected_output_bytes,input_sha256,expected_output_sha256 FROM content_validation_test_case WHERE snapshot_id=#{snapshot} ORDER BY sequence_no")
    int copyRevisionTests(@Param("draft")String draft,@Param("snapshot")String snapshot);
    @Select("SELECT reference_code FROM content_validation_snapshot WHERE id=#{snapshot}") String snapshotReference(String snapshot);
    @Select("SELECT COUNT(*) FROM public_problem_governance WHERE author_id=#{owner}") long ownedProblemCount(long owner);
    String PROBLEM="p.id,p.slug,p.title,p.status,g.version,g.author_id,g.snapshot_id,g.data_invalid,g.invalid_reason,g.state_reason,g.correction_of_id,p.current_judge_version_id AS judge_version_id";
    @Insert("INSERT IGNORE INTO public_problem_governance(problem_id) SELECT id FROM problem WHERE scope='PUBLIC'") int registerExistingPublic();
    String PROBLEM_FROM=" FROM public_problem_governance g JOIN problem p ON p.id=g.problem_id AND p.scope='PUBLIC' ";
    @Select("SELECT "+PROBLEM+PROBLEM_FROM+"WHERE g.author_id=#{owner} ORDER BY p.id DESC LIMIT #{size} OFFSET #{offset}")
    List<Problem> ownedProblems(@Param("owner")long owner,@Param("size")int size,@Param("offset")long offset);
    @Select("SELECT p.id"+PROBLEM_FROM+"WHERE p.slug=#{slug} AND g.author_id=#{owner}")
    Optional<Long> ownedProblemId(@Param("owner")long owner,@Param("slug")String slug);
    @Select("SELECT "+PROBLEM+PROBLEM_FROM+"WHERE p.id=#{id} FOR UPDATE") Optional<Problem> lockProblem(long id);
    @Select("<script>SELECT COUNT(*)"+PROBLEM_FROM+"<if test='status!=null'>WHERE p.status=#{status}</if></script>") long problemCount(String status);
    @Select("<script>SELECT "+PROBLEM+PROBLEM_FROM+"<if test='status!=null'>WHERE p.status=#{status}</if> ORDER BY p.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<Problem> problems(@Param("status")String status,@Param("size")int size,@Param("offset")long offset);
    @Insert("INSERT INTO problem(slug,title,statement_text,input_description,output_description,public_samples_json,status,scope) VALUES(#{slug},#{title},#{statement},#{input},#{output},CAST(#{samples} AS JSON),'ACTIVE','PUBLIC')")
    int problem(@Param("slug")String slug,@Param("title")String title,@Param("statement")String statement,@Param("input")String input,@Param("output")String output,@Param("samples")String samples);
    @Select("SELECT id FROM problem WHERE slug=#{slug} AND scope='PUBLIC'") long problemId(String slug);
    @Insert("INSERT INTO public_problem_governance(problem_id,author_id,current_review_id,snapshot_id,correction_of_id) VALUES(#{problem},#{owner},#{review},#{snapshot},#{correction})")
    int governance(@Param("problem")long problem,@Param("owner")long owner,@Param("review")String review,@Param("snapshot")String snapshot,@Param("correction")Long correction);
    @Insert("INSERT INTO official_problem_solution(judge_version_id,idea,language,source_code) VALUES(#{judge},#{idea},'JAVA_21',#{code})")
    int solution(@Param("judge")long judge,@Param("idea")String idea,@Param("code")String code);
    @Update("UPDATE official_problem_solution SET idea=#{idea},source_code=#{code},published_at=UTC_TIMESTAMP(6) WHERE judge_version_id=#{judge}")
    int solutionText(@Param("judge")long judge,@Param("idea")String idea,@Param("code")String code);
    @Update("UPDATE problem SET current_judge_version_id=#{judge} WHERE id=#{problem} AND scope='PUBLIC' AND current_judge_version_id IS NULL")
    int activate(@Param("problem")long problem,@Param("judge")long judge);
    @Update("UPDATE problem SET title=#{title},statement_text=#{statement},input_description=#{input},output_description=#{output},public_samples_json=CAST(#{samples} AS JSON) WHERE id=#{problem} AND scope='PUBLIC'")
    int text(@Param("problem")long problem,@Param("title")String title,@Param("statement")String statement,@Param("input")String input,@Param("output")String output,@Param("samples")String samples);
    @Update("UPDATE public_problem_governance SET current_review_id=#{review},snapshot_id=#{snapshot},version=version+1 WHERE problem_id=#{problem} AND version=#{version}")
    int updateRevision(@Param("problem")long problem,@Param("version")long version,@Param("review")String review,@Param("snapshot")String snapshot);
    @Update("UPDATE problem SET status=#{status} WHERE id=#{problem} AND scope='PUBLIC'") int status(@Param("problem")long problem,@Param("status")String status);
    @Update("UPDATE public_problem_governance SET version=version+1,state_reason=#{reason} WHERE problem_id=#{problem} AND version=#{version}")
    int bump(@Param("problem")long problem,@Param("version")long version,@Param("reason")String reason);
    @Update("UPDATE public_problem_governance SET version=version+1,data_invalid=TRUE,invalid_reason=#{reason},state_reason=#{reason} WHERE problem_id=#{problem} AND version=#{version} AND data_invalid=FALSE")
    int invalidate(@Param("problem")long problem,@Param("version")long version,@Param("reason")String reason);
    @Select("SELECT review_id,job_id,public_request_sha256 AS request_sha256 FROM public_review_recheck WHERE actor_admin_id=#{actor} AND client_request_id=#{request}")
    Optional<Recheck> recheck(@Param("actor")long actor,@Param("request")String request);
    @Select("SELECT COUNT(*) FROM public_review_recheck WHERE review_id=#{review}") long recheckCount(String review);
    @Insert("INSERT INTO public_review_recheck(actor_admin_id,client_request_id,review_id,job_id,public_request_sha256) VALUES(#{actor},#{request},#{review},#{job},#{hash})")
    int insertRecheck(@Param("actor")long actor,@Param("request")String request,@Param("review")String review,@Param("job")String job,@Param("hash")String hash);
    @Update("UPDATE content_review SET latest_recheck_id=#{job} WHERE id=#{review} AND review_status='PENDING'") int latest(@Param("review")String review,@Param("job")String job);
}
