/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.search;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SearchMapper {
    record Projection(long problemId,long dataVersion,boolean active,String slug,String title,String statementText,String difficulty,int judgeVersion) {}
    record Event(String id,long problemId,long dataVersion,long publicEpoch,int publishAttempts) {}
    record Control(long version,long readableEpoch,String indexName,String indexUuid,String activeJob) {}
    String PROJECT="SELECT p.id AS problemId,v.data_version AS dataVersion,(p.status='ACTIVE') AS active,p.slug,p.title,p.statement_text AS statementText,p.difficulty,COALESCE(j.version_no,0) AS judgeVersion FROM problem p JOIN public_search_version v ON v.problem_id=p.id LEFT JOIN problem_judge_version j ON j.id=p.current_judge_version_id WHERE p.scope='PUBLIC' ";
    @Insert("INSERT INTO public_search_version(problem_id,data_version) SELECT id,1 FROM problem WHERE id=#{id} AND scope='PUBLIC' ON DUPLICATE KEY UPDATE data_version=data_version") int initialize(long id);
    @Select("SELECT data_version FROM public_search_version WHERE problem_id=#{id} FOR UPDATE") Optional<Long> lockVersion(long id);
    @Update("UPDATE public_search_version SET data_version=data_version+1 WHERE problem_id=#{id} AND data_version=#{version} AND data_version<9007199254740991") int advance(@Param("id")long id,@Param("version")long version);
    @Insert("INSERT INTO public_search_outbox(id,problem_id,data_version,public_epoch) VALUES(#{event},#{id},#{version},#{epoch})") int append(@Param("event")String event,@Param("id")long id,@Param("version")long version,@Param("epoch")long epoch);
    @Select("SELECT COUNT(*) FROM public_search_outbox WHERE problem_id=#{id}") long eventCount(long id);
    @Select(PROJECT+"AND p.id=#{id}") Optional<Projection> projection(long id);
    @Select(PROJECT+"AND p.id>#{after} ORDER BY p.id LIMIT 100") List<Projection> scan(long after);
    @Select("<script>"+PROJECT+"AND p.id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>") List<Projection> projections(@Param("ids")List<Long> ids);
    @Select("SELECT tag FROM problem_tag WHERE problem_id=#{id} ORDER BY tag") List<String> tags(long id);
    @Select("SELECT DISTINCT pt.tag COLLATE utf8mb4_bin AS tag FROM problem_tag pt JOIN problem p ON p.id=pt.problem_id WHERE p.scope='PUBLIC' AND p.status='ACTIVE' AND pt.tag=#{tag} LIMIT 101") List<String> matchingTags(String tag);
    @Select("SELECT COUNT(*) FROM public_search_version") long projectionCount();
    @Select("SELECT version,readable_epoch AS readableEpoch,index_name AS indexName,index_uuid AS indexUuid,active_job AS activeJob FROM public_search_control WHERE id=1") Control control();
    @Select("SELECT version,readable_epoch AS readableEpoch,index_name AS indexName,index_uuid AS indexUuid,active_job AS activeJob FROM public_search_control WHERE id=1 FOR UPDATE") Control lockControl();
    @Update("UPDATE public_search_control SET readable_epoch=#{epoch} WHERE id=1 AND index_uuid=#{uuid} AND active_job IS NULL") int readable(@Param("uuid")String uuid,@Param("epoch")long epoch);
}
