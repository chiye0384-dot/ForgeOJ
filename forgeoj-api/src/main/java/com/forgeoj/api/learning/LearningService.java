/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.learning;

import static com.forgeoj.api.learning.LearningRecords.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.forgeoj.api.auth.AccountService;
import com.forgeoj.api.problem.ProblemLibraryMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LearningService {
    private static final long MAX_VERSION = 9007199254740991L;
    private final LearningMapper mapper;
    private final ProblemLibraryMapper tags;
    private final AccountService accounts;
    public LearningService(LearningMapper mapper, ProblemLibraryMapper tags, AccountService accounts) {
        this.mapper=mapper; this.tags=tags; this.accounts=accounts;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<ListSummary> lists(long userId,boolean official,int page,int size) {
        long offset=offset(page,size);
        long total=mapper.countLists(userId,official);
        var rows=offset>=total ? List.<LearningMapper.ListRow>of() : mapper.lists(userId,official,size,offset);
        Map<String,LearningMapper.Stats> stats=new HashMap<>();
        if(!rows.isEmpty()) mapper.pageStats(rows.stream().map(LearningMapper.ListRow::id).toList(),official,userId)
                .forEach(s->stats.put(s.id(),new LearningMapper.Stats(s.entryCount(),s.availableCount(),s.completedCount())));
        var items=rows.stream().map(row -> summary(row,userId,stats.getOrDefault(row.id(),new LearningMapper.Stats(0,0,0)))).toList();
        return new Page<>(items,page,size,total);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ListDetail detail(long userId,String id,boolean official,int page,int size) {
        long offset=offset(page,size);
        uuid(id);
        var row=mapper.list(userId,id,official).orElseThrow(LearningService::missing);
        var summary=summary(row,official,userId);
        var rows=offset>=summary.entryCount() ? List.<LearningMapper.EntryRow>of() : mapper.entries(userId,id,official,size,offset);
        Map<Long,List<String>> tagMap=new HashMap<>();
        var problemIds=rows.stream().filter(LearningMapper.EntryRow::available).map(LearningMapper.EntryRow::problemId).toList();
        if (!problemIds.isEmpty()) tags.tagsForProblems(problemIds).forEach(t -> tagMap.computeIfAbsent(t.problemId(), k -> new ArrayList<>()).add(t.tag()));
        var items=rows.stream().map(e -> new Entry(e.itemId(),e.position(),e.available(),e.available()
                ? new PublicProblem(e.slug(),e.title(),e.difficulty(),List.copyOf(tagMap.getOrDefault(e.problemId(),List.of())),e.judgeVersion(),userId>0 ? e.completed() : null)
                : null)).toList();
        return new ListDetail(summary,items,page,size,summary.entryCount());
    }
    private ListSummary summary(LearningMapper.ListRow row,boolean official,long userId) {
        var stats=mapper.stats(row.id(),official,userId);
        return summary(row,userId,stats);
    }
    private ListSummary summary(LearningMapper.ListRow row,long userId,LearningMapper.Stats stats) {
        return new ListSummary(row.id(),row.title(),row.description(),row.version(),stats.entryCount(),stats.availableCount(),userId>0 ? stats.completedCount():null,stats.entryCount()-stats.availableCount());
    }
    @Transactional
    public ListSummary create(long userId,TitleBody body) {
        String title=title(body.title());
        if (body.expectedVersion()!=null) invalid();
        accounts.requireCurrentWrite(userId);
        if (mapper.countLists(userId,false)>=100) conflict();
        String id=UUID.randomUUID().toString();
        mapper.createList(id,userId,title);
        return summary(new LearningMapper.ListRow(id,title,1,null),false,userId);
    }
    @Transactional
    public ListSummary rename(long userId,String id,TitleBody body) {
        String title=title(body.title());
        var row=lock(userId,id,body.expectedVersion());
        bump(userId,row,title);
        return summary(new LearningMapper.ListRow(id,title,row.version()+1,null),false,userId);
    }
    @Transactional
    public void delete(long userId,String id,Long version) {
        var row=lock(userId,id,version);
        mapper.clearItems(id,userId);
        if (mapper.deleteList(id,userId,row.version())!=1) conflict();
    }
    @Transactional
    public ListSummary add(long userId,String id,AddBody body) {
        var row=lock(userId,id,body.expectedVersion());
        var problem=publicProblem(body.problemSlug(),true);
        var ids=mapper.itemIds(id,userId);
        if(ids.size()>=1000) conflict();
        try { mapper.addItem(id,userId,UUID.randomUUID().toString(),problem.id(),ids.size()+1); }
        catch(DuplicateKeyException e) { conflict(); }
        bump(userId,row,row.title());
        return summary(new LearningMapper.ListRow(id,row.title(),row.version()+1,null),false,userId);
    }
    @Transactional
    public ListSummary remove(long userId,String id,String itemId,Long version) {
        uuid(itemId);
        var row=lock(userId,id,version);
        if(mapper.removeItem(id,userId,itemId)!=1) throw missing();
        reorder(userId,id,mapper.itemIds(id,userId));
        bump(userId,row,row.title());
        return summary(new LearningMapper.ListRow(id,row.title(),row.version()+1,null),false,userId);
    }
    @Transactional
    public ListSummary order(long userId,String id,OrderBody body) {
        var row=lock(userId,id,body.expectedVersion());
        var desired=body.itemIds();
        if(desired==null || desired.size()>1000 || desired.stream().anyMatch(Objects::isNull)) invalid();
        desired.forEach(LearningService::uuid);
        var current=mapper.itemIds(id,userId);
        if(desired.size()!=current.size() || new HashSet<>(desired).size()!=desired.size() || !new HashSet<>(desired).equals(new HashSet<>(current))) invalid();
        reorder(userId,id,desired);
        bump(userId,row,row.title());
        return summary(new LearningMapper.ListRow(id,row.title(),row.version()+1,null),false,userId);
    }
    private void reorder(long userId,String id,List<String> order) {
        mapper.shiftPositions(id,userId);
        for(int i=0;i<order.size();i++) if(mapper.setPosition(id,userId,order.get(i),i+1)!=1) conflict();
    }
    private LearningMapper.ListRow lock(long userId,String id,Long version) {
        uuid(id); expected(version,false);
        accounts.requireCurrentWrite(userId);
        var row=mapper.lockList(userId,id).orElseThrow(LearningService::missing);
        if(row.version()!=version || row.version()>=MAX_VERSION) conflict();
        return row;
    }
    private void bump(long userId,LearningMapper.ListRow row,String title) {
        if(mapper.changeList(row.id(),userId,title,row.version())!=1) conflict();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Draft draft(long userId,String slug,String language) {
        if(!"JAVA_21".equals(language)) invalid();
        slug(slug);
        var problem=mapper.problem(slug,false).orElseThrow(LearningService::missing);
        var draft=mapper.draft(userId,problem.id());
        if(draft.isPresent()) {
            var d=draft.get();
            return new Draft(d.language(),d.sourceCode(),d.version(),d.updatedAt(),problem.available());
        }
        if(!problem.available()) throw missing();
        return new Draft("JAVA_21",null,0,null,true);
    }
    @Transactional
    public Draft saveDraft(long userId,String slug,DraftBody body) {
        expected(body.expectedVersion(),true);
        if(!"JAVA_21".equals(body.language()) || body.sourceCode()==null) invalid();
        String source=body.sourceCode();
        if(source.indexOf('\0')>=0 || source.getBytes(StandardCharsets.UTF_8).length>65536) invalid();
        // Reject unpaired UTF-16 surrogates instead of silently storing replacement characters.
        for(int i=0;i<source.length();i++) {
            char c=source.charAt(i);
            if(Character.isHighSurrogate(c)) { if(i+1>=source.length() || !Character.isLowSurrogate(source.charAt(++i))) invalid(); }
            else if(Character.isLowSurrogate(c)) invalid();
        }
        accounts.requireCurrentWrite(userId);
        var problem=publicProblem(slug,true);
        if(body.expectedVersion()>=MAX_VERSION) conflict();
        if(body.expectedVersion()==0) {
            try { mapper.insertDraft(userId,problem.id(),source); }
            catch(DuplicateKeyException e) { conflict(); }
        } else if(mapper.updateDraft(userId,problem.id(),source,body.expectedVersion())!=1) conflict();
        var d=mapper.draft(userId,problem.id()).orElseThrow();
        return new Draft(d.language(),d.sourceCode(),d.version(),d.updatedAt(),true);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<History> history(long userId,String slug,int page,int size) {
        long offset=offset(page,size);
        if(slug!=null) publicProblem(slug,false);
        long total=mapper.historyCount(userId,slug);
        var rows=offset>=total ? List.<LearningMapper.HistoryRow>of() : mapper.history(userId,slug,size,offset);
        return new Page<>(rows.stream().map(r -> new History(r.submissionId(),r.createdAt(),r.language(),r.processingStatus(),r.statusVersion(),r.verdict(),r.judgeVersion(),r.slug()==null ? null : new HistoryProblem(r.slug(),r.title()),r.judgeDataWarning())).toList(),page,size,total);
    }
    private LearningMapper.ProblemRow publicProblem(String slug,boolean locking) {
        slug(slug);
        return mapper.problem(slug,locking).filter(LearningMapper.ProblemRow::available).orElseThrow(LearningService::missing);
    }
    private static void slug(String slug) { if(slug==null || !slug.matches("[a-z0-9][a-z0-9-]{0,99}")) throw missing(); }
    private static void uuid(String id) { if(id==null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw missing(); }
    private static String title(String value) {
        if(value==null) invalid();
        String title=value.strip();
        if(title.isEmpty() || title.codePointCount(0,title.length())>64 || title.indexOf('\0')>=0) invalid();
        return title;
    }
    private static long offset(int page,int size) { if(page<1 || size<1 || size>50) invalid(); return ((long)page-1)*size; }
    private static void expected(Long version,boolean zero) { if(version==null || version<(zero?0:1) || version>MAX_VERSION) invalid(); }
    private static void invalid() { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
    private static void conflict() { throw new ResponseStatusException(HttpStatus.CONFLICT); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
