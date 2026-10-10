package com.forgeoj.api.auth;

import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public final class AccountRateLimiter {
    private record Window(long start,int attempts) {}
    private final LinkedHashMap<String,Window> windows=new LinkedHashMap<>();
    private final int multiplier;
    private final com.forgeoj.api.cache.RedisSupport redis;
    public AccountRateLimiter(@org.springframework.beans.factory.annotation.Value("${forgeoj.auth.limits.multiplier:1}") int multiplier){
        this(multiplier,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AccountRateLimiter(@org.springframework.beans.factory.annotation.Value("${forgeoj.auth.limits.multiplier:1}") int multiplier,com.forgeoj.api.cache.RedisSupport redis){
        if(multiplier<1 || multiplier>100)throw new IllegalArgumentException("Invalid rate limit multiplier");this.multiplier=multiplier;this.redis=redis;
    }
    public void check(String key,int limit,long seconds) {
        if(limit<1||limit>100000||seconds<1||seconds>3600)throw new IllegalArgumentException("Invalid rate budget");
        local(key,limit,seconds);
        if(redis!=null && Boolean.FALSE.equals(redis.allow(key,limit*multiplier,seconds)))throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Try later");
    }
    private synchronized void local(String key,int limit,long seconds) {
        long now=System.nanoTime(),period=seconds*1_000_000_000L;
        windows.entrySet().removeIf(entry->now-entry.getValue().start()>3_600_000_000_000L);
        Window old=windows.get(key);
        if(old==null && windows.size()>=10000) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Try later");
        if(old==null || now-old.start()>=period) old=new Window(now,0);
        if(old.attempts()>=limit*multiplier) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Try later");
        windows.put(key,new Window(old.start(),old.attempts()+1));
    }
}
