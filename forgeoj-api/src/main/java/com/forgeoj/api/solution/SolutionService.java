/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.solution;

import com.forgeoj.api.auth.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SolutionService {
    private final SolutionMapper mapper;
    private final AccountService accounts;
    public SolutionService(SolutionMapper mapper,AccountService accounts) { this.mapper=mapper;this.accounts=accounts; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Access read(long userId,String slug) { return access(userId,version(slug,false)); }
    @Transactional
    public Access confirm(long userId,String slug,int expectedVersion) {
        if(expectedVersion<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        accounts.requireCurrentWrite(userId);
        var version=version(slug,true);
        if(version.judgeVersion()!=expectedVersion) throw new ResponseStatusException(HttpStatus.CONFLICT);
        if(!mapper.available(version.judgeVersionId())) throw missing();
        if(!mapper.completed(userId,version.problemId(),version.judgeVersionId())) {
            mapper.record(userId,version.judgeVersionId());
            if(!mapper.viewed(userId,version.judgeVersionId())) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return access(userId,version);
    }
    private SolutionMapper.Version version(String slug,boolean locking) {
        // Keep existing problem identities; bound input and bind SQL without a new slug format.
        if(slug==null || slug.isBlank() || slug.length()>80) throw missing();
        return mapper.version(slug,locking).orElseThrow(SolutionService::missing);
    }
    private Access access(long userId,SolutionMapper.Version v) {
        if(!mapper.available(v.judgeVersionId())) return new Access(v.judgeVersion(),"UNAVAILABLE",null);
        String access=mapper.completed(userId,v.problemId(),v.judgeVersionId()) ? "AC" : mapper.viewed(userId,v.judgeVersionId()) ? "EARLY_VIEW" : "LOCKED";
        var content=access.equals("LOCKED") ? null : mapper.content(v.judgeVersionId()).orElseThrow(()->new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        return new Access(v.judgeVersion(),access,content);
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
    public record Access(int judgeVersion,String access,SolutionMapper.Solution solution) {}
}
