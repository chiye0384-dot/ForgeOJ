/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;
import java.util.*;
import com.forgeoj.api.search.*;
import com.forgeoj.api.cache.CacheMapper;
import org.springframework.stereotype.Service;
import static com.forgeoj.api.admin.AdminInput.*;
@Service
public class SearchAdministrationService {
    public record Receipt(String id,String status,int attempts,String errorCode,String reason,String createdAt,String finishedAt) {}
    public record Status(boolean enabled,long version,long publicEpoch,long readableEpoch,long pendingEvents,long deadLetters,long failedPublications,long blockedDeadLetters,long failedDeadPublications,String activeRebuild) {}
    private final AdminService admins;private final AdminAudit audit;private final SearchMapper search;private final SearchRebuildMapper jobs;private final SearchDeliveryMapper deliveries;private final CacheMapper epochs;private final ElasticSearchClient es;
    public SearchAdministrationService(AdminService admins,AdminAudit audit,SearchMapper search,SearchRebuildMapper jobs,SearchDeliveryMapper deliveries,CacheMapper epochs,ElasticSearchClient es){this.admins=admins;this.audit=audit;this.search=search;this.jobs=jobs;this.deliveries=deliveries;this.epochs=epochs;this.es=es;}
    static Receipt receipt(SearchRebuildMapper.Job j){return new Receipt(j.id(),j.status(),j.attempts(),j.errorCode(),j.reason(),j.createdAt(),j.finishedAt());}
    public Status status(){return admins.operationsWork(a->{var c=search.control();audit.content("SEARCH_STATUS_READ",a.id(),"PUBLIC_SEARCH",null,"search status",null,null);return new Status(es.enabled(),c.version(),epochs.publicRevision(),c.readableEpoch(),deliveries.pendingCount(),deliveries.deadCount(),deliveries.failedPublications(),deliveries.blockedDeadLetters(),deliveries.failedDeadPublications(),c.activeJob());});}
    public AdminService.Page<Receipt> list(int page,int size){page(page,size);return admins.operationsWork(a->{audit.content("SEARCH_REBUILDS_READ",a.id(),"PUBLIC_SEARCH",null,"search rebuild metadata",null,null);return new AdminService.Page<>(jobs.page(size,((long)page-1)*size).stream().map(SearchAdministrationService::receipt).toList(),page,size,jobs.count());});}
    public Receipt rebuild(long expected,String request,String why){version(expected);if(expected>=MAX_VERSION-1)throw error(409);uuid(request);String reason=reason(why),hash=digest(expected+"\n"+reason);
        return admins.operationsWork(a->{
            var prior=jobs.prior(a.id(),request);if(prior.isPresent()){if(!prior.get().requestSha256().equals(hash))throw error(409);audit.content("SEARCH_REBUILD_REPLAY",a.id(),"PUBLIC_SEARCH_REBUILD",prior.get().id(),reason,null,null);return receipt(prior.get());}
            if(!es.enabled())throw error(503);var c=search.lockControl();if(c.version()!=expected||c.activeJob()!=null)throw error(409);
            String id=UUID.randomUUID().toString(),index="forgeoj-public-"+id.replace("-","");
            if(jobs.insert(id,a.id(),request,hash,reason,index)!=1||jobs.queue(id,expected)!=1)throw new IllegalStateException("Search rebuild CAS mismatch");
            audit.content("SEARCH_REBUILD_QUEUED",a.id(),"PUBLIC_SEARCH_REBUILD",id,reason,"version="+expected,"version="+(expected+1));return receipt(jobs.job(id).orElseThrow());
        });
    }
}
