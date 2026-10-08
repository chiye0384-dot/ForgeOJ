/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import com.forgeoj.api.auth.AccountRateLimiter;
import static com.forgeoj.api.admin.AdminInput.*;

@Service
public class AdminService {
    public record Login(AdminMapper.Account account,String sessionId,String refresh,LocalDateTime expiresAt) {}
    public record Page<T>(List<T> items,int page,int size,long total) {}
    private final AdminMapper mapper;
    private final PasswordEncoder passwords;
    private final AdminAudit audit;
    private final TransactionTemplate transaction;
    private final AccountRateLimiter limits;
    private final String dummy;
    public AdminService(AdminMapper mapper,PasswordEncoder passwords,AdminAudit audit,PlatformTransactionManager manager,@Value("${forgeoj.auth.limits.multiplier:1}")int multiplier){
        this.mapper=mapper;this.passwords=passwords;this.audit=audit;transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);limits=new AccountRateLimiter(multiplier);dummy=passwords.encode("unusable-admin-dummy-"+token());
    }
    public void rate(String key,int count,long seconds){limits.check("admin:"+key,count,seconds);}
    private <T>T work(Supplier<T> body){try{return transaction.execute(s->{mapper.fence();return body.get();});}catch(ResponseStatusException e){if(e.getStatusCode().value()==401||e.getStatusCode().value()==403||e.getStatusCode().value()==409)audit.denied(e.getStatusCode().value());throw e;}}
    public Login login(String name,String password){
        boolean valid=name!=null&&name.matches("[A-Za-z0-9_]{3,32}")&&password!=null&&password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72;
        if(!valid){passwords.matches("invalid",dummy);audit.denied(401);throw error(401);}
        return work(()->{
            var first=mapper.find(name.toLowerCase(Locale.ROOT));
            var a=first.isPresent()?mapper.lock(first.get().id()).orElseThrow():null;
            if(a==null||!passwords.matches(password,a==null?dummy:a.passwordHash())||!"ACTIVE".equals(a.status())){if(a==null)passwords.matches(password,dummy);throw error(401);}
            String sid=UUID.randomUUID().toString(),refresh=token();var expiry=mapper.now().plusHours(8);
            mapper.insertSession(new AdminMapper.Session(sid,a.id(),expiry,null));mapper.insertRefresh(digest(refresh),sid);
            audit.success("ADMIN_LOGIN",a.id(),Long.toString(a.id()),"login",null,state(a));return new Login(a,sid,refresh,expiry);
        });
    }
    public Login refresh(String raw){
        if(raw==null||!raw.matches("[A-Za-z0-9_-]{43}")){audit.denied(401);throw error(401);}
        Login login=work(()->{
            var r=mapper.refresh(digest(raw)).orElseThrow(()->error(401));var first=mapper.session(r.sessionId()).orElseThrow(()->error(401));
            var a=mapper.lock(first.adminId()).orElseThrow(()->error(401));var s=mapper.lockSession(first.id()).orElseThrow(()->error(401));var current=mapper.lockRefresh(digest(raw)).orElseThrow(()->error(401));
            if(current.consumedAt()!=null){mapper.revoke(s.id());audit.success("ADMIN_REFRESH_REUSE_REVOKED",a.id(),Long.toString(a.id()),"consumed refresh rejected",null,null);return null;}
            if(!"ACTIVE".equals(a.status())||s.revokedAt()!=null||!s.expiresAt().isAfter(mapper.now()))throw error(401);
            if(mapper.consume(digest(raw))!=1)throw new IllegalStateException("Admin refresh conflict");String next=token();mapper.insertRefresh(digest(next),s.id());
            audit.success("ADMIN_REFRESH",a.id(),Long.toString(a.id()),"refresh",null,null);return new Login(a,s.id(),next,s.expiresAt());
        });
        if(login==null)throw error(401);return login;
    }
    private AdminPrincipal principal(){var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||!(auth.getPrincipal() instanceof AdminPrincipal))throw error(auth!=null&&auth.isAuthenticated()?403:401);return (AdminPrincipal)auth.getPrincipal();}
    private AdminMapper.Account actor(boolean superOnly,boolean changing){
        var p=principal();var a=mapper.lock(p.id()).orElseThrow(()->error(401));var s=mapper.lockSession(p.sessionId()).orElseThrow(()->error(401));
        if(s.adminId()!=a.id()||!"ACTIVE".equals(a.status())||s.revokedAt()!=null||!s.expiresAt().isAfter(mapper.now()))throw error(401);
        if(!changing&&a.mustChangePassword()||superOnly&&!"SUPER_ADMIN".equals(a.role()))throw error(403);return a;
    }
    public void logoutCurrent(String refresh){
        work(()->{
            var auth=SecurityContextHolder.getContext().getAuthentication();
            if(auth!=null&&auth.getPrincipal() instanceof AdminPrincipal){var a=actor(false,true);mapper.revoke(principal().sessionId());audit.success("ADMIN_LOGOUT",a.id(),Long.toString(a.id()),"current session",null,null);}
            else if(refresh!=null&&refresh.matches("[A-Za-z0-9_-]{43}")){
                var r=mapper.refresh(digest(refresh));if(r.isPresent()){var s=mapper.session(r.get().sessionId()).orElseThrow();mapper.lock(s.adminId());mapper.lockSession(s.id());mapper.revoke(s.id());audit.success("ADMIN_LOGOUT",s.adminId(),Long.toString(s.adminId()),"refresh session",null,null);}
            }return null;
        });
    }
    public void logoutAll(){work(()->{var a=actor(false,true);mapper.revokeAll(a.id());audit.success("ADMIN_LOGOUT_ALL",a.id(),Long.toString(a.id()),"all sessions",null,null);return null;});}
    public void changePassword(String old,String next){password(next);work(()->{var a=actor(false,true);if(old==null||old.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72||!passwords.matches(old,a.passwordHash()))throw error(401);if(passwords.matches(next,a.passwordHash()))throw error(400);bump(a,a.status(),a.role(),passwords.encode(next),false);mapper.revokeAll(a.id());audit.success("ADMIN_PASSWORD_CHANGE",a.id(),Long.toString(a.id()),"own password changed",state(a),state(mapper.lock(a.id()).orElseThrow()));return null;});}
    public Page<AdminMapper.Summary> accounts(int page,int size){page(page,size);return work(()->{var a=actor(true,false);audit.success("ADMIN_ACCOUNTS_READ",a.id(),null,"account list",null,null);long total=mapper.count();return new Page<>(mapper.list(size,(long)(page-1)*size),page,size,total);});}
    public AdminMapper.Summary create(String request,String name,String role,String password,String reason){
        uuid(request);String n=username(name),r=role(role),why=reason(reason);String publicHash=digest(n+"\n"+r+"\n"+why);
        return work(()->{
            var actor=actor(true,false);var prior=mapper.creation(actor.id(),request);
            if(prior.isPresent()){if(!prior.get().publicRequestSha256().equals(publicHash))throw error(409);var original=mapper.lock(prior.get().targetId()).orElseThrow();audit.success("ADMIN_CREATE_REPLAY",actor.id(),Long.toString(original.id()),why,null,state(original));return summary(original);}
            if(mapper.count()>=1000||mapper.find(n).isPresent())throw error(409);
            AdminInput.password(password);mapper.insert(n,passwords.encode(password),r);long id=mapper.inserted();mapper.creationRequest(actor.id(),request,id,publicHash);var created=mapper.lock(id).orElseThrow();
            audit.success("ADMIN_CREATE",actor.id(),Long.toString(id),why,null,state(created));return summary(created);
        });
    }
    public AdminMapper.Summary mutate(long id,long version,String action,String role,String temporary,String reason){
        version(version);String why=reason(reason);if("ROLE".equals(action))role(role);if("RESET_PASSWORD".equals(action))password(temporary);
        return work(()->{
            var actor=actor(true,false);if(id<1||id>MAX_VERSION)throw error(404);var target=mapper.lock(id).orElseThrow(()->error(404));if(target.version()!=version)throw error(409);
            String status=target.status(),newRole=target.role(),hash=target.passwordHash();boolean must=target.mustChangePassword();
            switch(action){case "ROLE"->newRole=role;case "DISABLE"->status="DISABLED";case "RESTORE"->status="ACTIVE";case "RESET_PASSWORD"->{if(id==actor.id())throw error(403);if(passwords.matches(temporary,target.passwordHash()))throw error(400);hash=passwords.encode(temporary);must=true;}default->throw error(400);}
            if("ACTIVE".equals(target.status())&&"SUPER_ADMIN".equals(target.role())&&(!"ACTIVE".equals(status)||!"SUPER_ADMIN".equals(newRole))&&mapper.activeSupers()<=1)throw error(409);
            if(target.version()>=MAX_VERSION)throw error(409);bump(target,status,newRole,hash,must);mapper.revokeAll(id);var updated=mapper.lock(id).orElseThrow();
            audit.success("ADMIN_"+action,actor.id(),Long.toString(id),why,state(target),state(updated));return summary(updated);
        });
    }
    public Page<AdminMapper.Event> events(int page,int size,String action,Long actorId,String target,LocalDateTime from,LocalDateTime to){
        page(page,size);if(action!=null&&!action.matches("[A-Z_]{1,40}")||actorId!=null&&actorId<1||target!=null&&!target.matches("[1-9][0-9]{0,15}")||from!=null&&to!=null&&!from.isBefore(to))throw error(400);
        return work(()->{var a=actor(true,false);audit.success("ADMIN_AUDIT_READ",a.id(),null,"audit list",null,null);long total=mapper.eventCount(action,actorId,target,from,to);return new Page<>(mapper.events(action,actorId,target,from,to,size,(long)(page-1)*size),page,size,total);});
    }
    private void bump(AdminMapper.Account a,String status,String role,String hash,boolean must){if(mapper.update(a.id(),a.version(),status,role,hash,must)!=1)throw error(409);}
    static AdminMapper.Summary summary(AdminMapper.Account a){return new AdminMapper.Summary(a.id(),a.username(),a.status(),a.role(),a.mustChangePassword(),a.version());}
    static String state(AdminMapper.Account a){return "status="+a.status()+";role="+a.role()+";mustChangePassword="+a.mustChangePassword()+";version="+a.version();}
}
