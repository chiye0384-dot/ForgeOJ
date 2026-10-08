/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.classroom;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class TeacherRecordService {
    private final AssignmentService assignments;
    private final TeacherRecordMapper mapper;
    public TeacherRecordService(AssignmentService assignments,TeacherRecordMapper mapper) {this.assignments=assignments;this.mapper=mapper;}
    public record Grade(int ordinal,String slug,String title,long judgeVersionId,String state,int attempts,String firstAcAt) {}
    public record Participant(long userId,String username,String memberStatus,String role,int completed,List<Grade> problems) {}
    public record Grades(AssignmentService.Summary assignment,String classroomTitle,String classroomStatus,List<Participant> items,int page,int size,long total) {}
    public record Attempt(String submissionId,long userId,int ordinal,String problemSlug,long judgeVersionId,String language,String processingStatus,String verdict,String acceptedAt,String finishedAt) {}
    public record Attempts(List<Attempt> items,int page,int size,long total) {}
    public record Source(Attempt submission,String sourceCode,String sourceSha256) {}
    public Grades grades(long user,String room,String id,int page,int size) {
        paging(page,size);var context=assignments.teachingContext(user,room,id);var assignment=context.assignment();
        long total=mapper.participantCount(id),offset=(long)(page-1)*size;
        var people=offset>=total?List.<TeacherRecordMapper.Participant>of():mapper.participants(id,size,offset);
        var grouped=new HashMap<Long,List<Grade>>();
        if(!people.isEmpty()) for(var row:mapper.grades(id,people.stream().map(TeacherRecordMapper.Participant::userId).toList())) {
            var cutoff=assignment.endedAt()==null||assignment.deadlineAt().isBefore(assignment.endedAt())?assignment.deadlineAt():assignment.endedAt();
            String state=row.dataInvalid()?"INVALID":row.precompleted()?"PRECOMPLETED":row.acceptedAt()!=null?(row.acceptedAt().isBefore(cutoff)?"ON_TIME_AC":"LATE_AC"):row.attempts()==0?"NOT_STARTED":"ATTEMPTING";
            grouped.computeIfAbsent(row.userId(),unused->new ArrayList<>()).add(new Grade(row.ordinal(),row.slug(),row.title(),row.judgeVersionId(),state,row.attempts(),row.precompleted()?null:time(row.firstAcAt())));
        }
        var items=people.stream().map(p->{var grades=List.copyOf(grouped.getOrDefault(p.userId(),List.of()));int completed=(int)grades.stream().filter(g->Set.of("PRECOMPLETED","ON_TIME_AC","LATE_AC").contains(g.state())).count();return new Participant(p.userId(),p.username(),p.memberStatus(),p.role(),completed,grades);}).toList();
        return new Grades(assignments.summary(assignment),context.classroomTitle(),context.classroomStatus(),items,page,size,total);
    }
    public Attempts attempts(long user,String room,String id,long target,int ordinal,int page,int size) {
        paging(page,size);assignments.teachingContext(user,room,id);
        if(target<=0||target>ClassroomService.MAX_VERSION||ordinal<1||ordinal>20||!mapper.scope(id,target,ordinal)) throw ClassroomService.missing();
        long total=mapper.attemptCount(id,target,ordinal),offset=(long)(page-1)*size;
        var rows=offset>=total?List.<TeacherRecordMapper.Attempt>of():mapper.attempts(id,target,ordinal,size,offset);
        return new Attempts(rows.stream().map(this::attempt).toList(),page,size,total);
    }
    public Source source(long user,String room,String id,String submission) {
        assignments.teachingContext(user,room,id);ClassroomService.uuid(submission);
        var s=mapper.source(id,submission).orElseThrow(ClassroomService::missing);
        return new Source(new Attempt(s.submissionId(),s.userId(),s.ordinal(),s.problemSlug(),s.judgeVersionId(),s.language(),s.processingStatus(),s.verdict(),time(s.acceptedAt()),time(s.finishedAt())),s.sourceCode(),s.sourceSha256());
    }
    private Attempt attempt(TeacherRecordMapper.Attempt a) {return new Attempt(a.submissionId(),a.userId(),a.ordinal(),a.problemSlug(),a.judgeVersionId(),a.language(),a.processingStatus(),a.verdict(),time(a.acceptedAt()),time(a.finishedAt()));}
    private static String time(LocalDateTime value) {return value==null?null:value.toString()+"Z";}
    private static void paging(int page,int size) {if(page<1||size<1||size>50) throw ClassroomService.bad();}
}
