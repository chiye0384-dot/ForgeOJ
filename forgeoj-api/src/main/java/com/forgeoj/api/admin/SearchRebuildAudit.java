/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;
import com.forgeoj.api.search.SearchRebuildMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class SearchRebuildAudit {
    private final AdminAudit audit;
    public SearchRebuildAudit(AdminAudit audit){this.audit=audit;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void terminal(SearchRebuildMapper.Job job,String result){
        if(!java.util.Set.of("SUCCEEDED","FAILED").contains(result))throw new IllegalArgumentException("Invalid search audit result");
        audit.content("SEARCH_REBUILD_"+result,job.actorId(),"PUBLIC_SEARCH_REBUILD",job.id(),job.reason(),"attempt="+job.attempts(),result);
    }
}
