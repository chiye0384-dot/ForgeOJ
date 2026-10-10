/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.cache;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Optional, bounded Redis access. A Redis result is never an authorization or acceptance fact. */
@Component
public class RedisSupport {
    public static final String PREFIX="forgeoj:v1:";
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final long cooldownNanos;
    private final AtomicLong unavailableUntil=new AtomicLong();
    private final java.util.concurrent.Semaphore probes=new java.util.concurrent.Semaphore(1);
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end",Long.class);
    private static final DefaultRedisScript<Long> RATE=new DefaultRedisScript<>(
            "local n=tonumber(redis.call('GET',KEYS[1]) or '0'); if n>=tonumber(ARGV[1]) then return 0 end; n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[2]) end; return 1",Long.class);
    private static final DefaultRedisScript<Long> PUBLISH=new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end; if tonumber(redis.call('GET',KEYS[4]) or '0')>=tonumber(ARGV[5]) then return 0 end; if redis.call('SCARD',KEYS[3])>=4096 then return 0 end; redis.call('SET',KEYS[2],ARGV[2],'PX',ARGV[3]); redis.call('SADD',KEYS[3],KEYS[2]); redis.call('PEXPIRE',KEYS[3],ARGV[4]); return 1",Long.class);
    private static final DefaultRedisScript<Long> INVALIDATE=new DefaultRedisScript<>(
            "if tonumber(redis.call('GET',KEYS[2]) or '0')<tonumber(ARGV[1]) then redis.call('SET',KEYS[2],ARGV[1]) end; local keys=redis.call('SPOP',KEYS[1],100); for _,k in ipairs(keys) do redis.call('UNLINK',k) end; local n=redis.call('SCARD',KEYS[1]); if n==0 then redis.call('DEL',KEYS[1]) end; return n",Long.class);

    public RedisSupport(StringRedisTemplate redis,@Value("${forgeoj.redis.enabled:false}") boolean enabled,
            @Value("${forgeoj.redis.cooldown-ms:2000}") long cooldownMs) {
        if(cooldownMs<100 || cooldownMs>30000) throw new IllegalArgumentException("Invalid Redis cooldown");
        this.redis=redis;this.enabled=enabled;cooldownNanos=cooldownMs*1_000_000L;
    }
    /** null means unavailable, including a bounded half-open probe already in progress. */
    private <T>T call(Supplier<T> action) {
        if(!enabled) return null;
        long failed=unavailableUntil.get(),now=System.nanoTime();
        if(failed!=0 && now-failed<0) return null;
        boolean probe=failed!=0;
        if(probe&&!probes.tryAcquire()) return null;
        try {T value=action.get();unavailableUntil.set(0);return value;}
        catch(org.springframework.dao.DataAccessException | io.lettuce.core.RedisException failure) {
            unavailableUntil.set(System.nanoTime()+cooldownNanos);return null;
        } finally {if(probe) probes.release();}
    }
    public String get(String key) {return call(()->redis.opsForValue().get(key));}
    public boolean enabled(){return enabled;}
    public void delete(String key) {call(()->redis.delete(key));}
    public boolean set(String key,String value,Duration ttl) {
        return Boolean.TRUE.equals(call(()->{redis.opsForValue().set(key,value,ttl);return true;}));
    }
    public String acquire(String key,Duration ttl) {
        String token=UUID.randomUUID().toString();
        return Boolean.TRUE.equals(call(()->redis.opsForValue().setIfAbsent(key,token,ttl)))?token:null;
    }
    public void release(String key,String token) {if(token!=null)call(()->redis.execute(RELEASE,List.of(key),token));}
    public Boolean allow(String key,int limit,long seconds) {
        Long value=call(()->redis.execute(RATE,List.of(PREFIX+"rate:"+digest(key)),Integer.toString(limit),Long.toString(seconds*1000)));
        return value==null?null:value==1;
    }
    public boolean publish(String lock,String token,String key,String index,long revision,String json,Duration ttl) {
        return Long.valueOf(1).equals(call(()->redis.execute(PUBLISH,List.of(lock,key,index,PREFIX+"public:retired-through"),token,json,
                Long.toString(ttl.toMillis()),"360000",Long.toString(revision))));
    }
    public Long invalidate(String index,long revision) {return call(()->redis.execute(INVALIDATE,List.of(index,PREFIX+"public:retired-through"),Long.toString(revision)));}
    public static String digest(String key) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
