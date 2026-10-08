/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.classroom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AssignmentLifecycle {
    private final AssignmentMapper mapper;
    private final ClassroomMapper rooms;
    public AssignmentLifecycle(AssignmentMapper mapper,ClassroomMapper rooms) {this.mapper=mapper;this.rooms=rooms;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void refresh(String room) {
        mapper.fence();var classroom=rooms.lock(room).orElse(null);if(classroom==null) return;
        if(classroom.status().equals("ACTIVE")) refreshLocked(room);else archiveLocked(room);
    }
    // Caller already holds policy fence and classroom lock. All database times here are UTC.
    public void refreshLocked(String room) {
        for(var row:mapper.open(room)) {
            var now=mapper.now();
            if(row.status().equals("SCHEDULED")&&!row.startsAt().isAfter(now)) {
                if(!row.deadlineAt().isAfter(now)) {one(mapper.close(row.id(),"STOPPED",now,"开始前已超过截止时间"));continue;}
                if(!valid(room,row.id())) {one(mapper.close(row.id(),"STOPPED",now,"题目不可用或判题版本已变化，请复制并重新核对"));continue;}
                start(row,now);
            } else if(row.status().equals("ACTIVE")&&!row.allowLate()&&!row.deadlineAt().isAfter(now)) {
                one(mapper.close(row.id(),"ENDED",row.deadlineAt(),"硬截止"));
            }
        }
    }
    public boolean valid(String room,String id) {
        var list=mapper.problems(id);if(list.isEmpty()) return false;
        return list.stream().allMatch(p->mapper.candidate(room,p.problemSlug()).filter(c->c.problemId()==p.problemId()&&c.judgeVersionId()==p.judgeVersionId()).isPresent());
    }
    public void start(AssignmentMapper.Row row,java.time.LocalDateTime now) {
        start(row,now,row.startsAt()==null?now:row.startsAt());
    }
    public void start(AssignmentMapper.Row row,java.time.LocalDateTime now,java.time.LocalDateTime planned) {
        int count=mapper.problems(row.id()).size();
        one(mapper.publish(row.id(),"ACTIVE",planned,now));
        if(mapper.freeze(row.id())!=count||count==0) throw new IllegalStateException("Assignment problem freeze mismatch");
        mapper.snapshot(row.id(),row.classroomId(),now);
        if(row.acceptExistingAc()) mapper.precomplete(row.id(),null,now.plusSeconds(mapper.databaseOffsetSeconds()));
    }
    public void archiveLocked(String room) {
        var now=mapper.now();
        for(var row:mapper.open(room)) one(mapper.close(row.id(),row.startedAt()==null?"STOPPED":"ENDED",now,"班级归档"));
    }
    private static void one(int count) {if(count!=1) throw new IllegalStateException("Assignment lifecycle conflict");}
}
