/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.util.*;
import org.springframework.stereotype.Service;
import static com.forgeoj.api.admin.AdminInput.*;

@Service
public class OperationsService {
    private static final Set<String> KINDS=Set.of("FORMAL","VALIDATE","OUTPUT_PREVIEW","SELF_TEST");
    private static final Set<String> STATUSES=Set.of("QUEUED","RUNNING","RETRYING","WAITING_RETRY","FINISHED","CANCELLED","SYSTEM_ERROR","DEAD_LETTER");
    private final OperationsMapper mapper;
    private final AdminService admins;
    private final AdminAudit audit;
    public OperationsService(OperationsMapper mapper,AdminService admins,AdminAudit audit){this.mapper=mapper;this.admins=admins;this.audit=audit;}
    public AdminService.Page<OperationsMapper.Task> list(int page,int size,String kind,String id,String status){
        page(page,size);if(kind!=null)kind(kind);if(id!=null)uuid(id);if(status!=null&&!STATUSES.contains(status))throw error(400);
        return admins.operationsWork(a->{audit.content("OPS_TASKS_READ",a.id(),"EXECUTION_TASK",null,"exception metadata list",null,null);
            long count=mapper.count(kind,id,status);return new AdminService.Page<>(mapper.list(kind,id,status,size,(long)(page-1)*size),page,size,count);});
    }
    public OperationsMapper.Task detail(String kind,String id){kind(kind);readId(id);return admins.operationsWork(a->{
        var task=mapper.task(kind,id).orElseThrow(()->error(404));audit.content("OPS_TASK_READ",a.id(),"EXECUTION_TASK",id,"bound task metadata",null,null);return task;});}
    public AdminService.Page<OperationsMapper.Attempt> attempts(String kind,String id,int page,int size){kind(kind);readId(id);page(page,size);return admins.operationsWork(a->{
        mapper.task(kind,id).orElseThrow(()->error(404));audit.content("OPS_ATTEMPTS_READ",a.id(),"EXECUTION_TASK",id,"bound attempt metadata",null,null);
        return new AdminService.Page<>(mapper.attempts(kind,id,size,(long)(page-1)*size),page,size,mapper.attemptCount(kind,id));});}
    public AdminService.Page<OperationsMapper.Event> events(String kind,String id,int page,int size){kind(kind);readId(id);page(page,size);return admins.operationsWork(a->{
        mapper.task(kind,id).orElseThrow(()->error(404));audit.content("OPS_OUTBOX_READ",a.id(),"EXECUTION_TASK",id,"bound delivery metadata",null,null);
        return new AdminService.Page<>(mapper.events(kind,id,size,(long)(page-1)*size),page,size,mapper.eventCount(kind,id));});}
    public AdminService.Page<OperationsMapper.Recovery> recoveries(String kind,String id,int page,int size){kind(kind);readId(id);page(page,size);return admins.operationsWork(a->{
        mapper.task(kind,id).orElseThrow(()->error(404));audit.content("OPS_RECOVERIES_READ",a.id(),"EXECUTION_TASK",id,"bound recovery history",null,null);
        return new AdminService.Page<>(mapper.recoveries(kind,id,size,(long)(page-1)*size),page,size,mapper.recoveryCount(kind,id));});}
    static void kind(String kind){if(kind==null||!KINDS.contains(kind))throw error(400);}
    static void readId(String id){try{uuid(id);}catch(org.springframework.web.server.ResponseStatusException invalid){throw error(404);}}
}
