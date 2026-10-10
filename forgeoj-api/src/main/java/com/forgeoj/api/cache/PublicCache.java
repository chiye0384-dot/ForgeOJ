/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.cache;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JavaType;
@Component
public class PublicCache {
    public record Entry(String requestDigest,String body,String bodySha256) {}
    private final CacheMapper mapper;private final RedisSupport redis;private final ObjectMapper json;
    // Fixed stripes bound memory even for arbitrary missing slugs.
    private final Object[] locks=new Object[256];
    public PublicCache(CacheMapper mapper,RedisSupport redis,ObjectMapper json){
        this.mapper=mapper;this.redis=redis;this.json=json;java.util.Arrays.setAll(locks,i->new Object());
    }
    public static String index(long revision){return RedisSupport.PREFIX+"public:"+revision+":index";}
    public <T>T read(String request,Class<T> type,Supplier<T> load){return read(request,json.constructType(type),load);}
    public <T>T read(String request,JavaType type,Supplier<T> load) {
        if(!redis.enabled())return load.get();
        long revision=mapper.publicRevision();
        String digest=RedisSupport.digest(request);
        String key=RedisSupport.PREFIX+"public:"+revision+":"+digest;
        synchronized(locks[Math.floorMod(key.hashCode(),locks.length)]) {
            String cached=redis.get(key);
            if(cached!=null) {
                try{return decode(cached,digest,type);}catch(InvalidCache | tools.jackson.core.JacksonException invalid){redis.delete(key);}
            }
            String lock=key+":lock",token=redis.acquire(lock,Duration.ofSeconds(3));
            if(token==null && redis.get(lock)!=null) {
                for(int attempt=0;attempt<3;attempt++) {
                    try{Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
                    String filled=redis.get(key);
                    if(filled!=null) {
                        try{return decode(filled,digest,type);}catch(InvalidCache | tools.jackson.core.JacksonException invalid){redis.delete(key);break;}
                    }
                }
            }
            try {
                T value=load.get();
                String body=json.writeValueAsString(value);
                if(token!=null&&body.length()<=1048576)redis.publish(lock,token,key,index(revision),revision,encode(digest,body),Duration.ofSeconds(ThreadLocalRandom.current().nextLong(240,301)));
                return value;
            }catch(ResponseStatusException absent){
                if(absent.getStatusCode().value()==404&&token!=null)redis.publish(lock,token,key,index(revision),revision,encode(digest,"null"),Duration.ofSeconds(ThreadLocalRandom.current().nextLong(8,13)));
                throw absent;
            }finally{redis.release(lock,token);}
        }
    }
    private String encode(String request,String body){return json.writeValueAsString(new Entry(request,body,RedisSupport.digest(body)));}
    private <T>T decode(String value,String request,JavaType type) {
        if(value.length()>2097152)throw new InvalidCache();
        Entry entry=json.readValue(value,Entry.class);
        if(entry==null||!request.equals(entry.requestDigest())||entry.body()==null||entry.body().length()>1048576
                ||!RedisSupport.digest(entry.body()).equals(entry.bodySha256()))throw new InvalidCache();
        if(entry.body().equals("null"))throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        T result=json.readValue(entry.body(),type);if(result==null)throw new InvalidCache();return result;
    }
    private static final class InvalidCache extends RuntimeException {}
}
