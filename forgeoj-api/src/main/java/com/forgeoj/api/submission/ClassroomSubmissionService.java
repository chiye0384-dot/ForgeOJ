/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.submission;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import com.forgeoj.api.auth.AccountService;
import com.forgeoj.api.classroom.ClassroomProblemService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClassroomSubmissionService {
    private final SubmissionMapper mapper;
    private final SubmissionTransactionService transactions;
    private final AccountService accounts;
    private final ClassroomProblemService problems;
    public ClassroomSubmissionService(SubmissionMapper mapper,SubmissionTransactionService transactions,AccountService accounts,ClassroomProblemService problems) {this.mapper=mapper;this.transactions=transactions;this.accounts=accounts;this.problems=problems;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public SubmissionResult create(long user,String room,String slug,String request,String language,String code) {
        UUID key;try {key=UUID.fromString(request);if(!key.toString().equals(request)) throw new IllegalArgumentException();}catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
        if(!"JAVA_21".equals(language)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        SubmissionService.validateSource(code);accounts.requireCurrentWrite(user);
        if(mapper.lockQuota(user).isEmpty()) throw new IllegalStateException("Missing quota lock");
        problems.authorize(user,room,false,true);
        var version=mapper.classroomVersion(room,slug).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        String hash;try {hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        var existing=mapper.findResultByRequest(user,request);
        if(existing.isPresent()) {
            if(!mapper.matches(user,request,version.problemId(),hash)) throw new ResponseStatusException(HttpStatus.CONFLICT);
            return existing.get();
        }
        if(mapper.countQueuedOrRetrying(user)>=3) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        return transactions.enqueue(user,version,key,language,code,hash);
    }
}
