/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.search;

import java.util.UUID;
import com.forgeoj.api.cache.CacheMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SearchChanges {
    private final SearchMapper mapper;
    private final CacheMapper epochs;
    public SearchChanges(SearchMapper mapper,CacheMapper epochs){this.mapper=mapper;this.epochs=epochs;}
    /** Call after public cache epoch is advanced, in the same governance transaction. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void changed(long id){
        mapper.initialize(id);
        long version=mapper.lockVersion(id).orElseThrow(()->new IllegalStateException("Public search scope mismatch"));
        if(mapper.eventCount(id)>0){
            if(mapper.advance(id,version)!=1)throw new IllegalStateException("Public search version exhausted");
            version++;
        }
        mapper.append(UUID.randomUUID().toString(),id,version,epochs.publicRevision());
    }
}
