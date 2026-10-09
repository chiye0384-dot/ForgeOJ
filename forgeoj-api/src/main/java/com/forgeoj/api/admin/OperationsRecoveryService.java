/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import static com.forgeoj.api.admin.AdminInput.*;

@Service
public class OperationsRecoveryService {
    public record Input(long expectedVersion,String clientRequestId,String reason,Integer expectedPublishAttempts) {}
    private final OperationsRecoveryMapper mapper;
    private final OperationsMapper queries;
    private final AdminService admins;
    private final AdminAudit audit;
    private final JsonMapper json=JsonMapper.builder().build();
    public OperationsRecoveryService(OperationsRecoveryMapper mapper,OperationsMapper queries,AdminService admins,AdminAudit audit){this.mapper=mapper;this.queries=queries;this.admins=admins;this.audit=audit;}
    public OperationsRecoveryMapper.Receipt recover(String kind,String id,String event,Input input){
        return admins.operationsWork(actor->{
            OperationsService.kind(kind);uuid(id);if(input==null)throw error(400);
            uuid(input.clientRequestId());String why=reason(input.reason());
            if(input.expectedVersion()<0||input.expectedVersion()>=MAX_VERSION)throw error(400);
            String scope=event==null?"EXECUTION":"DELIVERY",target=event==null?id:uuid(event);
            if(event==null&&input.expectedPublishAttempts()!=null||event!=null&&(input.expectedPublishAttempts()==null||input.expectedPublishAttempts()<0))throw error(400);
            String hash=digest(scope+"\n"+kind+"\n"+id+"\n"+target+"\n"+input.expectedVersion()+"\n"+input.expectedPublishAttempts()+"\n"+why);
            var prior=mapper.prior(actor.id(),input.clientRequestId());
            if(prior.isPresent()){
                if(!hash.equals(prior.get().requestSha256()))throw error(409);
                audit.content("OPS_RECOVERY_REPLAY",actor.id(),"EXECUTION_TASK",id,why,null,null);return prior.get().receipt();
            }
            var initial=queries.task(kind,id).orElseThrow(()->error(404));
            if(!"ACTIVE".equals(mapper.account(initial.ownerId()).orElseThrow(()->error(409))))throw error(409);
            // Same lock order as user acceptance/cancellation. Terminal retries cannot consume active leases.
            mapper.quota(initial.ownerId()).orElseThrow(()->error(409));
            var state=switch(kind){case "FORMAL"->mapper.formal(id);case "SELF_TEST"->mapper.self(id);default->mapper.content(kind,id);};
            var s=state.orElseThrow(()->error(404));
            if(s.version()!=input.expectedVersion()||s.ownerId()!=initial.ownerId()||!s.noLease()||s.version()>=MAX_VERSION||mapper.used(scope,kind,target)!=0)throw error(409);
            eligibility(kind,s);
            String eventId;int attempts;String failure,finished;long resultingVersion;
            if(event==null){
                if(!s.status().equals(kind.equals("FORMAL")?"DEAD_LETTER":"SYSTEM_ERROR")||s.attempts()<1||s.attempts()!=s.maxAttempts()||s.maxAttempts()>=10
                    ||!Set.of("PLATFORM_FAILURE","LEASE_EXPIRED","ATTEMPT_LIMIT_EXHAUSTED").contains(s.failureCode()==null?"":s.failureCode()))throw error(409);
                int active=switch(kind){case "FORMAL"->mapper.formalActive(id);case "SELF_TEST"->mapper.selfActive(id);default->mapper.contentActive(id);};
                if(active!=0||mapper.retryableLast(kind,id,s.attempts())!=1||mapper.pending(s.ownerId())>=3)throw error(409);
                if(kind.equals("FORMAL")){
                    if(!mapper.submissionFailed(s.binding()))throw error(409);
                    changed(mapper.requeueFormal(s));changed(mapper.requeueSubmission(s.binding()));
                }else if(kind.equals("SELF_TEST"))changed(mapper.requeueSelf(s));else changed(mapper.requeueContent(s));
                eventId=UUID.randomUUID().toString();
                int sequence=s.sequence()+1;
                changed(mapper.outbox(eventId,aggregate(kind),prefix(kind)+"_QUEUED",sequence,payload(kind,s),s));
                attempts=s.attempts();failure=s.failureCode();finished=s.finishedAt();resultingVersion=s.version()+1;
            }else{
                var d=mapper.delivery(event).orElseThrow(()->error(404));
                if(!d.taskId().equals(id)||!d.aggregateType().equals(aggregate(kind))||d.contractVersion()!=1||!d.unpublished()||d.failedAt()==null||d.attempts()!=input.expectedPublishAttempts())throw error(409);
                boolean queued=d.type().equals(prefix(kind)+"_QUEUED")&&Set.of("QUEUED","RETRYING","WAITING_RETRY").contains(s.status());
                boolean dead=d.type().equals(prefix(kind)+"_DEAD_LETTERED")&&s.status().equals(kind.equals("FORMAL")?"DEAD_LETTER":"SYSTEM_ERROR");
                int currentSequence=kind.equals("FORMAL")&&queued?s.attempts():s.sequence();
                // Manual formal recovery uses count+1 while the extra execution has not been claimed.
                if(kind.equals("FORMAL")&&queued&&mapper.used("EXECUTION",kind,id)!=0)currentSequence=s.attempts()+1;
                if((!queued&&!dead)||d.sequence()!=currentSequence||!contract(kind,s,d.payload()))throw error(409);
                changed(mapper.rearm(event,d.attempts()));eventId=event;attempts=d.attempts();failure=d.failureCode();finished=d.failedAt();resultingVersion=s.version();
            }
            String receipt=UUID.randomUUID().toString();
            changed(mapper.receipt(receipt,actor.id(),input.clientRequestId(),hash,scope,kind,s,target,eventId,attempts,failure,finished,resultingVersion,why));
            audit.content("OPS_"+scope+"_RECOVERY",actor.id(),"EXECUTION_TASK",id,why,
                json.writeValueAsString(Map.of("status",s.status(),"version",s.version(),"attempts",attempts,"failureCode",failure==null?"NONE":failure)),
                json.writeValueAsString(Map.of("receiptId",receipt,"eventId",eventId,"version",resultingVersion)));
            return mapper.prior(actor.id(),input.clientRequestId()).orElseThrow().receipt();
        });
    }
    private void eligibility(String kind,OperationsRecoveryMapper.State s){
        if(kind.equals("VALIDATE")||kind.equals("OUTPUT_PREVIEW")){
            var draft=mapper.draft(s.binding()).orElseThrow(()->error(409));
            if(!draft.status().equals("DRAFT")||draft.version()!=draft.snapshotVersion())throw error(409);
            var review=mapper.review(draft.id());
            if(review.isPresent()){
                var r=review.get();
                if(r.status().equals("PENDING")){
                    if(!kind.equals("VALIDATE")||!s.id().equals(r.latestJob()))throw error(409);
                }else if(s.binding().equals(r.snapshotId()))throw error(409);
            }
        }else{
            if(kind.equals("SELF_TEST")&&!s.payloadValid())throw error(409);
            mapper.assignmentFence();
            var basis=kind.equals("FORMAL")?mapper.formalBasis(s.binding()):mapper.selfBasis(s.id(),s.binding());
            if(basis==null)throw error(409);
            if(basis.classroomId()!=null){
                if(!"ACTIVE".equals(mapper.room(basis.classroomId()).orElseThrow(()->error(409)))||!mapper.member(basis.classroomId(),s.ownerId()))throw error(409);
            }
            if(basis.assignmentId()!=null){
                var status=mapper.assignment(basis.assignmentId()).orElseThrow(()->error(409));
                if(!Set.of("ACTIVE","ENDED").contains(status)||!mapper.participant(basis.assignmentId(),s.ownerId()))throw error(409);
            }
            if(!"ACTIVE".equals(mapper.problem(basis.problemId()).orElseThrow(()->error(409)))||mapper.invalid(basis.problemId()))throw error(409);
        }
    }
    private boolean contract(String kind,OperationsRecoveryMapper.State s,String payload){
        try{
            var t=json.readTree(payload);var names=new HashSet<String>();t.propertyNames().forEach(names::add);
            String binding=kind.equals("FORMAL")?"submissionId":"snapshotId";
            return t.isObject()&&names.equals(Set.of("taskId",binding,"taskType","contractVersion"))
                &&t.get("taskId").isString()&&t.get("taskId").stringValue().equals(s.id())
                &&t.get(binding).isString()&&t.get(binding).stringValue().equals(s.binding())
                &&t.get("taskType").isString()&&t.get("taskType").stringValue().equals(taskType(kind))
                &&t.get("contractVersion").isIntegralNumber()&&t.get("contractVersion").canConvertToInt()&&t.get("contractVersion").intValue()==1;
        }catch(RuntimeException invalid){return false;}
    }
    private String payload(String kind,OperationsRecoveryMapper.State s){return json.writeValueAsString(Map.of("taskId",s.id(),kind.equals("FORMAL")?"submissionId":"snapshotId",s.binding(),"taskType",taskType(kind),"contractVersion",1));}
    private String taskType(String kind){return kind.equals("FORMAL")?"JUDGE_SUBMISSION":kind.equals("SELF_TEST")?"SELF_TEST":"CONTENT_VALIDATE";}
    private String aggregate(String kind){return kind.equals("FORMAL")?"JUDGE_TASK":kind.equals("SELF_TEST")?"SELF_TEST":"CONTENT_VALIDATION";}
    private String prefix(String kind){return aggregate(kind);}
    private void changed(int n){if(n!=1)throw error(409);}
}
