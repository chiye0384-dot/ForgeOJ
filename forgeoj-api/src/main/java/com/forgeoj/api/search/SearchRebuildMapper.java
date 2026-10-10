/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.search;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface SearchRebuildMapper {
    record Job(String id,long actorId,String clientRequestId,String requestSha256,String reason,String status,String targetIndex,String targetUuid,Long targetEpoch,int attempts,String leaseToken,boolean expired,String errorCode,String createdAt,String finishedAt) {}
    String COLUMNS="id,actor_id AS actorId,client_request_id AS clientRequestId,request_sha256 AS requestSha256,reason,status,target_index AS targetIndex,target_uuid AS targetUuid,target_epoch AS targetEpoch,attempts,lease_token AS leaseToken,(lease_expires_at IS NULL OR lease_expires_at<=UTC_TIMESTAMP(6)) AS expired,error_code AS errorCode,CAST(created_at AS CHAR) AS createdAt,CAST(finished_at AS CHAR) AS finishedAt";
    @Select("SELECT "+COLUMNS+" FROM public_search_rebuild WHERE actor_id=#{actor} AND client_request_id=#{request}") Optional<Job> prior(@Param("actor")long actor,@Param("request")String request);
    @Select("SELECT "+COLUMNS+" FROM public_search_rebuild WHERE id=#{id} FOR UPDATE") Optional<Job> lock(String id);
    @Select("SELECT "+COLUMNS+" FROM public_search_rebuild WHERE id=#{id}") Optional<Job> job(String id);
    @Select("SELECT "+COLUMNS+" FROM public_search_rebuild ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}") List<Job> page(@Param("size")int size,@Param("offset")long offset);
    @Select("SELECT COUNT(*) FROM public_search_rebuild") long count();
    @Insert("INSERT INTO public_search_rebuild(id,actor_id,client_request_id,request_sha256,reason,target_index) VALUES(#{id},#{actor},#{request},#{hash},#{reason},#{index})") int insert(@Param("id")String id,@Param("actor")long actor,@Param("request")String request,@Param("hash")String hash,@Param("reason")String reason,@Param("index")String index);
    @Update("UPDATE public_search_control SET active_job=#{job},version=version+1 WHERE id=1 AND version=#{version} AND active_job IS NULL") int queue(@Param("job")String job,@Param("version")long version);
    @Update("UPDATE public_search_rebuild SET status=IF(status='READY','READY','RUNNING'),attempts=attempts+1,lease_token=#{token},lease_expires_at=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)) WHERE id=#{id} AND attempts<5 AND status IN ('QUEUED','RUNNING','READY')") int claim(@Param("id")String id,@Param("token")String token);
    @Update("UPDATE public_search_rebuild SET lease_token=#{token},lease_expires_at=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)) WHERE id=#{id} AND attempts=5 AND status='READY' AND target_uuid IS NOT NULL AND target_epoch IS NOT NULL AND (lease_expires_at IS NULL OR lease_expires_at<=UTC_TIMESTAMP(6))") int finalRecoveryLease(@Param("id")String id,@Param("token")String token);
    @Update("UPDATE public_search_rebuild SET lease_expires_at=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)) WHERE id=#{id} AND lease_token=#{token} AND lease_expires_at>UTC_TIMESTAMP(6) AND status IN ('RUNNING','READY')") int renew(@Param("id")String id,@Param("token")String token);
    @Update("UPDATE public_search_rebuild SET status='READY',target_uuid=#{uuid},target_epoch=#{epoch} WHERE id=#{id} AND lease_token=#{token} AND lease_expires_at>UTC_TIMESTAMP(6) AND status IN ('RUNNING','READY')") int ready(@Param("id")String id,@Param("token")String token,@Param("uuid")String uuid,@Param("epoch")long epoch);
    @Update("UPDATE public_search_control SET active_job=NULL,index_name=#{index},index_uuid=#{uuid},readable_epoch=#{epoch},version=version+1 WHERE id=1 AND active_job=#{id}") int switched(@Param("id")String id,@Param("index")String index,@Param("uuid")String uuid,@Param("epoch")long epoch);
    @Update("UPDATE public_search_rebuild SET status='SUCCEEDED',lease_token=NULL,lease_expires_at=NULL,finished_at=UTC_TIMESTAMP(6),error_code=NULL WHERE id=#{id} AND status='READY' AND lease_token=#{token} AND lease_expires_at>UTC_TIMESTAMP(6)") int succeeded(@Param("id")String id,@Param("token")String token);
    @Update("UPDATE public_search_rebuild SET status=IF(attempts>=5,'FAILED',status),error_code='REBUILD_UNAVAILABLE',lease_token=NULL,lease_expires_at=NULL,finished_at=IF(attempts>=5,UTC_TIMESTAMP(6),NULL) WHERE id=#{id} AND lease_token=#{token}") int failed(@Param("id")String id,@Param("token")String token);
    @Update("UPDATE public_search_rebuild SET status='FAILED',lease_token=NULL,lease_expires_at=NULL,error_code='LEASE_EXHAUSTED',finished_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND attempts>=5 AND status IN ('QUEUED','RUNNING','READY') AND (lease_expires_at IS NULL OR lease_expires_at<=UTC_TIMESTAMP(6))") int exhausted(String id);
    @Update("UPDATE public_search_control SET active_job=NULL,version=version+1 WHERE id=1 AND active_job=#{id}") int clear(String id);
}
