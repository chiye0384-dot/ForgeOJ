/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.cache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class CacheInvalidations {
    private final CacheMapper mapper;
    public CacheInvalidations(CacheMapper mapper){this.mapper=mapper;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void publicChanged(){long before=mapper.lockPublic();mapper.advance();mapper.append(before);}
}
