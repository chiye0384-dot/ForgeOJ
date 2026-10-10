/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.cache;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
/** Short hint only; every caller still executes authoritative DB authorization/idempotency. */
@Component
public class RequestCoalescer {
    private final RedisSupport redis;
    private final Object[] stripes=new Object[256];
    public RequestCoalescer(RedisSupport redis){this.redis=redis;java.util.Arrays.setAll(stripes,i->new Object());}
    public <T>T execute(long user,String request,Supplier<T> action) {
        String key=RedisSupport.PREFIX+"inflight:"+RedisSupport.digest(user+":"+request);
        synchronized(stripes[Math.floorMod(key.hashCode(),stripes.length)]) {
            String token=redis.acquire(key,Duration.ofSeconds(2));
            if(token==null && redis.get(key)!=null) {
                // Bounded courtesy wait; an expired or failed lease cannot reject an accepted request.
                try{Thread.sleep(30);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            }
            try{return action.get();}finally{redis.release(key,token);}
        }
    }
}
