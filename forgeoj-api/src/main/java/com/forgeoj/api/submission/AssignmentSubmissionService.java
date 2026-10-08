/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.submission;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import com.forgeoj.api.auth.AccountService;
import com.forgeoj.api.classroom.AssignmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AssignmentSubmissionService {
    private final SubmissionMapper mapper;
    private final SubmissionTransactionService transactions;
    private final AccountService accounts;
    private final AssignmentService assignments;
    public AssignmentSubmissionService(SubmissionMapper mapper,SubmissionTransactionService transactions,AccountService accounts,AssignmentService assignments) {this.mapper=mapper;this.transactions=transactions;this.accounts=accounts;this.assignments=assignments;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public SubmissionResult create(long user,String room,String assignment,String slug,String request,String language,String code) {
        UUID key;try {key=UUID.fromString(request);if(!key.toString().equals(request)) throw new IllegalArgumentException();}catch(RuntimeException e){throw status(400);}
        if(!"JAVA_21".equals(language)) throw status(400);SubmissionService.validateSource(code);accounts.requireCurrentWrite(user);
        if(mapper.lockQuota(user).isEmpty()) throw new IllegalStateException("Missing quota lock");
        var previous=mapper.findResultByRequest(user,request);
        var prepared=assignments.prepare(user,room,assignment,slug,previous.isPresent());var p=prepared.problem();
        String hash;try {hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        if(previous.isPresent()) {if(!mapper.matches(user,request,p.problemId(),hash)||!assignments.linked(user,prepared,previous.get().submissionId())) throw status(409);return previous.get();}
        if(mapper.countQueuedOrRetrying(user)>=3) throw status(429);
        var frozen=mapper.frozenVersion(p.problemId(),p.judgeVersionId()).orElseThrow(()->status(404));
        var accepted=assignments.acceptance(prepared);
        var result=transactions.enqueue(user,frozen,key,language,code,hash);assignments.link(user,prepared,result.submissionId(),accepted);return result;
    }
    private static ResponseStatusException status(int code) {return new ResponseStatusException(HttpStatus.valueOf(code));}
}
