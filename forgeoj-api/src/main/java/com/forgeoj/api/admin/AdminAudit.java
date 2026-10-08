/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import com.forgeoj.api.auth.ForgeOjPrincipal;

@Service
public class AdminAudit {
    private final AdminMapper mapper;
    private final TransactionTemplate independent;
    public AdminAudit(AdminMapper mapper,PlatformTransactionManager manager){this.mapper=mapper;independent=new TransactionTemplate(manager);independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
    void success(String action,Long actor,String target,String reason,String before,String after){
        mapper.audit(new AdminMapper.Event(UUID.randomUUID().toString(),null,"ADMIN",actor,action,"ADMIN_ACCOUNT",target,"SUCCESS",reason,before,after,correlation()));
    }
    public void denied(int status){
        var auth=SecurityContextHolder.getContext().getAuthentication();var p=auth==null?null:auth.getPrincipal();
        String type=p instanceof AdminPrincipal?"ADMIN":p instanceof ForgeOjPrincipal?"DENIED_USER":"AUTH_ANONYMOUS";
        Long actor=p instanceof AdminPrincipal a?a.id():null;
        independent.executeWithoutResult(s->mapper.audit(new AdminMapper.Event(UUID.randomUUID().toString(),null,type,actor,"ADMIN_ACCESS_DENIED","NONE",null,"DENIED","HTTP_"+status,null,null,correlation())));
    }
    private String correlation(){String value=org.slf4j.MDC.get("requestId");try{return UUID.fromString(value).toString();}catch(RuntimeException invalid){return UUID.randomUUID().toString();}}
}
