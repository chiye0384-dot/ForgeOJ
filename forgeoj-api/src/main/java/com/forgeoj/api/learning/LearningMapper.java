/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.learning;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface LearningMapper {
    // The table choice is a boolean supplied only by the service, never SQL input from a client.
    String LIST_TABLE = "<choose><when test='official'>official_problem_list</when><otherwise>personal_problem_list</otherwise></choose>";
    String ITEM_TABLE = "<choose><when test='official'>official_problem_list_item</when><otherwise>personal_problem_list_item</otherwise></choose>";
    String VISIBLE_LIST = "<choose><when test='official'>l.status = 'ACTIVE'</when><otherwise>l.owner_id = #{userId}</otherwise></choose>";
    String AVAILABLE = "p.scope = 'PUBLIC' AND p.status = 'ACTIVE' AND jv.id IS NOT NULL";
    String AC = "EXISTS(SELECT 1 FROM submission s WHERE s.user_id = #{userId} AND s.problem_id=p.id AND s.judge_version_id=p.current_judge_version_id AND s.processing_status='FINISHED' AND s.verdict='AC')";
    String STATS = " SELECT COUNT(*) AS entryCount, COALESCE(SUM(" + AVAILABLE + "),0) AS availableCount, COALESCE(SUM(("+ AVAILABLE + ") AND " + AC + "),0) AS completedCount FROM " + ITEM_TABLE + " i JOIN problem p ON p.id=i.problem_id LEFT JOIN problem_judge_version jv ON jv.id=p.current_judge_version_id WHERE i.list_id=#{id}";

    @Select("<script>SELECT COUNT(*) FROM " + LIST_TABLE + " l WHERE " + VISIBLE_LIST + "</script>")
    long countLists(@Param("userId") long userId, @Param("official") boolean official);
    @Select("<script>SELECT l.id,l.title,l.version,<choose><when test='official'>l.description</when><otherwise>NULL</otherwise></choose> AS description FROM " + LIST_TABLE + " l WHERE " + VISIBLE_LIST + " ORDER BY l.created_at DESC,l.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<ListRow> lists(@Param("userId") long userId,@Param("official") boolean official,@Param("size") int size,@Param("offset") long offset);
    @Select("<script>SELECT l.id,l.title,l.version,<choose><when test='official'>l.description</when><otherwise>NULL</otherwise></choose> AS description FROM " + LIST_TABLE + " l WHERE l.id=#{id} AND " + VISIBLE_LIST + "</script>")
    Optional<ListRow> list(@Param("userId") long userId,@Param("id") String id,@Param("official") boolean official);
    @Select("SELECT id,title,version,NULL AS description FROM personal_problem_list WHERE id=#{id} AND owner_id=#{userId} FOR UPDATE")
    Optional<ListRow> lockList(@Param("userId") long userId,@Param("id") String id);
    @Select("<script>" + STATS + "</script>")
    Stats stats(@Param("id") String id,@Param("official") boolean official,@Param("userId") long userId);
    @Select("<script>SELECT i.list_id AS id,COUNT(*) AS entryCount,COALESCE(SUM(" + AVAILABLE + "),0) AS availableCount,COALESCE(SUM((" + AVAILABLE + ") AND " + AC + "),0) AS completedCount FROM " + ITEM_TABLE + " i JOIN " + LIST_TABLE + " l ON l.id=i.list_id JOIN problem p ON p.id=i.problem_id LEFT JOIN problem_judge_version jv ON jv.id=p.current_judge_version_id WHERE " + VISIBLE_LIST + " AND l.id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> GROUP BY i.list_id</script>")
    List<StatsRow> pageStats(@Param("ids") List<String> ids,@Param("official") boolean official,@Param("userId") long userId);
    @Select("<script>SELECT i.id AS itemId,i.position,p.id AS problemId,p.slug,p.title,p.difficulty,jv.version_no AS judgeVersion,(" + AVAILABLE + ") AS available," + AC + " AS completed FROM " + ITEM_TABLE + " i JOIN " + LIST_TABLE + " l ON l.id=i.list_id JOIN problem p ON p.id=i.problem_id LEFT JOIN problem_judge_version jv ON jv.id=p.current_judge_version_id WHERE l.id=#{id} AND " + VISIBLE_LIST + " ORDER BY i.position LIMIT #{size} OFFSET #{offset}</script>")
    List<EntryRow> entries(@Param("userId") long userId,@Param("id") String id,@Param("official") boolean official,@Param("size") int size,@Param("offset") long offset);
    @Insert("INSERT INTO personal_problem_list(id,owner_id,title) VALUES(#{id},#{userId},#{title})")
    int createList(@Param("id") String id,@Param("userId") long userId,@Param("title") String title);
    @Update("UPDATE personal_problem_list SET title=#{title},version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND owner_id=#{userId} AND version=#{version}")
    int changeList(@Param("id") String id,@Param("userId") long userId,@Param("title") String title,@Param("version") long version);
    @Delete("DELETE i FROM personal_problem_list_item i JOIN personal_problem_list l ON l.id=i.list_id WHERE l.id=#{id} AND l.owner_id=#{userId}")
    int clearItems(@Param("id") String id,@Param("userId") long userId);
    @Delete("DELETE FROM personal_problem_list WHERE id=#{id} AND owner_id=#{userId} AND version=#{version}")
    int deleteList(@Param("id") String id,@Param("userId") long userId,@Param("version") long version);
    @Select("SELECT i.id FROM personal_problem_list_item i JOIN personal_problem_list l ON l.id=i.list_id WHERE l.id=#{id} AND l.owner_id=#{userId} ORDER BY i.position")
    List<String> itemIds(@Param("id") String id,@Param("userId") long userId);
    @Insert("INSERT INTO personal_problem_list_item(id,list_id,problem_id,position) SELECT #{itemId},l.id,#{problemId},#{position} FROM personal_problem_list l WHERE l.id=#{id} AND l.owner_id=#{userId}")
    int addItem(@Param("id") String id,@Param("userId") long userId,@Param("itemId") String itemId,@Param("problemId") long problemId,@Param("position") int position);
    @Delete("DELETE i FROM personal_problem_list_item i JOIN personal_problem_list l ON l.id=i.list_id WHERE l.id=#{id} AND l.owner_id=#{userId} AND i.id=#{itemId}")
    int removeItem(@Param("id") String id,@Param("userId") long userId,@Param("itemId") String itemId);
    @Update("UPDATE personal_problem_list_item i JOIN personal_problem_list l ON l.id=i.list_id SET i.position=i.position+2000 WHERE l.id=#{id} AND l.owner_id=#{userId}")
    int shiftPositions(@Param("id") String id,@Param("userId") long userId);
    @Update("UPDATE personal_problem_list_item i JOIN personal_problem_list l ON l.id=i.list_id SET i.position=#{position} WHERE l.id=#{id} AND l.owner_id=#{userId} AND i.id=#{itemId}")
    int setPosition(@Param("id") String id,@Param("userId") long userId,@Param("itemId") String itemId,@Param("position") int position);
    @Select("<script>SELECT p.id,(" + AVAILABLE + ") AS available FROM problem p LEFT JOIN problem_judge_version jv ON jv.id=p.current_judge_version_id WHERE p.slug=#{slug}<if test='locking'> FOR SHARE</if></script>")
    Optional<ProblemRow> problem(@Param("slug") String slug,@Param("locking") boolean locking);
    @Select("SELECT language,source_code AS sourceCode,version,updated_at AS updatedAt FROM user_code_draft WHERE user_id=#{userId} AND problem_id=#{problemId} AND language='JAVA_21'")
    Optional<DraftRow> draft(@Param("userId") long userId,@Param("problemId") long problemId);
    @Insert("INSERT INTO user_code_draft(user_id,problem_id,language,source_code) VALUES(#{userId},#{problemId},'JAVA_21',#{source})")
    int insertDraft(@Param("userId") long userId,@Param("problemId") long problemId,@Param("source") String source);
    @Update("UPDATE user_code_draft SET source_code=#{source},version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE user_id=#{userId} AND problem_id=#{problemId} AND language='JAVA_21' AND version=#{version}")
    int updateDraft(@Param("userId") long userId,@Param("problemId") long problemId,@Param("source") String source,@Param("version") long version);
    String HISTORY_FROM=" FROM submission s JOIN problem p ON p.id=s.problem_id JOIN problem_judge_version old ON old.id=s.judge_version_id LEFT JOIN problem_judge_version jv ON jv.id=p.current_judge_version_id WHERE s.user_id=#{userId}<if test='slug != null'> AND p.slug=#{slug} AND " + AVAILABLE + "</if>";
    @Select("<script>SELECT COUNT(*)" + HISTORY_FROM + "</script>")
    long historyCount(@Param("userId") long userId,@Param("slug") String slug);
    @Select("<script>SELECT s.id AS submissionId,s.created_at AS createdAt,s.language,s.processing_status AS processingStatus,s.status_version AS statusVersion,CASE WHEN s.processing_status='FINISHED' THEN s.verdict END AS verdict,old.version_no AS judgeVersion,CASE WHEN " + AVAILABLE + " THEN p.slug END AS slug,CASE WHEN " + AVAILABLE + " THEN p.title END AS title" + HISTORY_FROM + " ORDER BY s.created_at DESC,s.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<HistoryRow> history(@Param("userId") long userId,@Param("slug") String slug,@Param("size") int size,@Param("offset") long offset);

    record ListRow(String id,String title,long version,String description) {}
    record Stats(long entryCount,long availableCount,long completedCount) {}
    record StatsRow(String id,long entryCount,long availableCount,long completedCount) {}
    record EntryRow(String itemId,int position,long problemId,String slug,String title,String difficulty,Integer judgeVersion,boolean available,boolean completed) {}
    record ProblemRow(long id,boolean available) {}
    record DraftRow(String language,String sourceCode,long version,LocalDateTime updatedAt) {}
    record HistoryRow(String submissionId,LocalDateTime createdAt,String language,String processingStatus,long statusVersion,String verdict,int judgeVersion,String slug,String title) {}
}
