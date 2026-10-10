/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.cache;
import java.time.Duration;
import java.util.Optional;
import com.forgeoj.api.auth.ForgeOjPrincipal;
import com.forgeoj.api.admin.AdminPrincipal;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
@Component
public class SessionCache {
    public record Identity(String username) {}
    private final SessionCacheMapper mapper;private final RedisSupport redis;private final ObjectMapper json;
    public SessionCache(SessionCacheMapper mapper,RedisSupport redis,ObjectMapper json){this.mapper=mapper;this.redis=redis;this.json=json;}
    public Optional<ForgeOjPrincipal> ordinary(long id,String sid) {
        var guard=mapper.ordinary(sid);if(guard.isEmpty()||guard.get().id()!=id)return Optional.empty();
        String name=name(false,sid,guard.get());
        return name==null?Optional.empty():Optional.of(new ForgeOjPrincipal(id,name,"",true,sid));
    }
    public Optional<AdminPrincipal> admin(long id,String sid) {
        var guard=mapper.admin(sid);if(guard.isEmpty()||guard.get().id()!=id)return Optional.empty();
        var g=guard.get();String name=name(true,sid,g);
        return name==null?Optional.empty():Optional.of(new AdminPrincipal(id,name,g.role(),g.mustChangePassword(),sid));
    }
    private String name(boolean admin,String sid,SessionCacheMapper.Guard guard) {
        String key=RedisSupport.PREFIX+"session:"+(admin?"admin:":"ordinary:")+guard.id()+":"+guard.version()+":"+RedisSupport.digest(sid);
        String value=redis.get(key);
        if(value!=null)try{var identity=json.readValue(value,Identity.class);if(identity!=null&&valid(identity.username()))return identity.username();}catch(tools.jackson.core.JacksonException invalid){redis.delete(key);}
        String current=admin?mapper.adminName(guard.id()):mapper.ordinaryName(guard.id());
        if(!valid(current))return null;
        redis.set(key,json.writeValueAsString(new Identity(current)),Duration.ofSeconds(60));
        return current;
    }
    // Authenticate existing database identities too; registration validation is a separate rule.
    private boolean valid(String name){return name!=null&&!name.isBlank()&&name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=256;}
}
