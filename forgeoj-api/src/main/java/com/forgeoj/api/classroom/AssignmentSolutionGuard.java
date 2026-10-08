/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.classroom;

import org.springframework.stereotype.Service;

@Service
public class AssignmentSolutionGuard {
    private final AssignmentMapper mapper;
    public AssignmentSolutionGuard(AssignmentMapper mapper) {this.mapper=mapper;}
    // Must be called in the transaction, before acquiring any classroom/problem lock.
    public void lock() {if(mapper.fence()!=1) throw new IllegalStateException("Missing assignment policy fence");}
    public boolean allowed(long user,long problem) {
        var now=mapper.now();
        for(var row:mapper.restrictions(user,problem)) {
            var p=mapper.problems(row.id()).stream().filter(v->v.problemId()==problem).findFirst().orElseThrow();
            boolean open=switch(row.solutionPolicy()) {
                case "IMMEDIATE" -> true;
                case "AFTER_AC" -> mapper.precompleted(row.id(),user,p.problemId()).isPresent()||mapper.ac(row.id(),user,p.problemId()).isPresent();
                case "AFTER_DEADLINE" -> !row.deadlineAt().isAfter(now)||(row.endedAt()!=null&&!row.endedAt().isAfter(now));
                default -> throw new IllegalStateException("Invalid assignment policy");
            };
            if(!open) return false;
        }return true;
    }
}
