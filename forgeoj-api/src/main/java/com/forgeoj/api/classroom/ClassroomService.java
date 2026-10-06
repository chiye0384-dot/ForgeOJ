/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.classroom;

import static com.forgeoj.api.classroom.ClassroomMapper.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import com.forgeoj.api.auth.AccountService;
import com.forgeoj.api.auth.AccountRateLimiter;
import com.forgeoj.api.auth.AccountMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class ClassroomService {
    static final long MAX_VERSION=9007199254740991L;
    private final ClassroomMapper mapper;
    private final AccountService accounts;
    private final AccountRateLimiter limits;
    private final AccountMapper accountMapper;
    private final SecureRandom random=new SecureRandom();
    public record Page(List<Summary> items,int page,int size,long total) {}
    public record Detail(String id,String title,String status,long ownerId,long version,String role,boolean inviteEnabled,List<Member> members,Transfer pendingTransfer) {}
    public record Invite(String inviteCode,long version) {}
    public ClassroomService(ClassroomMapper mapper,AccountService accounts,AccountRateLimiter limits,AccountMapper accountMapper) {this.mapper=mapper;this.accounts=accounts;this.limits=limits;this.accountMapper=accountMapper;}
    public Page list(long user,int page,int size) {
        if(page<1 || size<1 || size>50) throw bad();
        accounts.requireCurrentWrite(user);
        long total=mapper.count(user),offset=(long)(page-1)*size;
        return new Page(offset>=total?List.of():mapper.list(user,size,offset),page,size,total);
    }
    public Detail create(long user,String title,String request) {
        title=title(title);requestId(request);accounts.requireCurrentWrite(user);
        var prior=mapper.creation(user,request);
        if(prior.isPresent()) {
            if(!prior.get().title().equals(title)) throw conflict();
            var room=mapper.lock(prior.get().classroomId()).orElseThrow(ClassroomService::missing);
            return detail(user,room);
        }
        if(mapper.owned(user)>=100) throw conflict();
        String id=UUID.randomUUID().toString();mapper.create(id,user,title);mapper.insertMember(id,user,"OWNER");mapper.creationRequest(user,request,id,title);
        return detail(user,mapper.lock(id).orElseThrow());
    }
    public Detail get(long user,String id) {return detail(user,lock(user,id));}
    private Detail detail(long user,Room room) {
        var self=effective(user,room);
        boolean owner=room.ownerId()==user;
        return new Detail(room.id(),room.title(),room.status(),room.ownerId(),room.version(),self.role(),owner&&room.inviteEnabled(),mapper.members(room.id(),owner),mapper.pending(room.id()).filter(t->owner||t.targetUserId()==user).orElse(null));
    }
    public Detail rename(long user,String id,String title,long version) {
        title=title(title);var room=owner(user,id,version,false);bump(new Room(id,room.ownerId(),title,room.status(),room.inviteSha256(),room.inviteEnabled(),room.version()));return detail(user,mapper.lock(id).orElseThrow());
    }
    public Invite invite(long user,String id,boolean enabled,long version) {
        var room=owner(user,id,version,false);String code=null,hash=null;
        if(enabled) {byte[] bytes=new byte[24];random.nextBytes(bytes);code=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);hash=digest(code);}
        bump(new Room(id,room.ownerId(),room.title(),room.status(),hash,enabled,room.version()));return new Invite(code,room.version()+1);
    }
    public Detail join(long user,String code) {
        accounts.requireCurrentWrite(user);limits.check("classroom-join:"+user,10,60);
        if(code==null||!code.matches("[A-Za-z0-9_-]{32}")) throw missing();
        String hash=digest(code);String id=mapper.invite(hash).orElseThrow(ClassroomService::missing);
        var room=mapper.lock(id).orElseThrow(ClassroomService::missing);
        if(!room.status().equals("ACTIVE")||!room.inviteEnabled()||!hash.equals(room.inviteSha256())) throw missing();
        var self=mapper.member(id,user);
        if(self.isPresent()&&self.get().status().equals("REMOVED")) throw missing();
        if(self.isPresent()&&self.get().status().equals("ACTIVE")) return detail(user,room);
        if(self.isEmpty()) {if(mapper.memberCount(id)>=1000) throw conflict();mapper.insertMember(id,user,"MEMBER");}
        else mapper.changeMember(id,user,"MEMBER","ACTIVE");
        bump(room);return detail(user,mapper.lock(id).orElseThrow());
    }
    public Detail memberAction(long user,String id,long target,String action,String role,long version) {
        if(target<=0||target>MAX_VERSION) throw missing();
        var room=owner(user,id,version,false);
        if(target==room.ownerId()) throw conflict();
        var member=mapper.member(id,target).orElseThrow(ClassroomService::missing);
        if(action.equals("role")) {
            if(!Set.of("MEMBER","ASSISTANT").contains(role==null?"":role)) throw bad();
            if(!member.status().equals("ACTIVE")) throw conflict();
            mapper.changeMember(id,target,role,"ACTIVE");
        } else if(action.equals("remove")) {
            if(!member.status().equals("ACTIVE")) throw conflict();
            mapper.changeMember(id,target,member.role(),"REMOVED");withdrawPending(id,target);
        } else if(action.equals("restore")) {
            if(member.status().equals("ACTIVE")) throw conflict();
            mapper.changeMember(id,target,"MEMBER","ACTIVE");
        } else throw missing();
        bump(room);return detail(user,mapper.lock(id).orElseThrow());
    }
    public void leave(long user,String id,long version) {
        var room=lock(user,id);var member=mapper.member(id,user).orElseThrow(ClassroomService::missing);
        if(member.status().equals("LEFT")) return;
        if(!member.status().equals("ACTIVE")) throw missing();
        expected(room,version);
        if(room.ownerId()==user&&room.status().equals("ACTIVE")) throw conflict();
        mapper.changeMember(id,user,member.role(),"LEFT");withdrawPending(id,user);bump(room);
    }
    public Transfer propose(long user,String id,long target,String request,long version) {
        requestId(request);uuid(id);
        if(target==user||target<=0||target>MAX_VERSION) throw conflict();
        // Inserting target's FK implicitly locks its account. Acquire both parents before
        // the classroom in ascending order, so a joining target cannot deadlock with us.
        accountMapper.lockAccount(Math.min(user,target)).orElseThrow(ClassroomService::missing);
        accountMapper.lockAccount(Math.max(user,target)).orElseThrow(ClassroomService::missing);
        var room=lock(user,id);effective(user,room);
        if(room.ownerId()!=user) throw forbidden();
        var previous=mapper.transferRequest(user,request);
        if(previous.isPresent()) {
            var t=previous.get();if(!t.classroomId().equals(id)||t.targetUserId()!=target) throw conflict();return t;
        }
        expected(room,version);active(room);
        var m=mapper.member(id,target).orElseThrow(ClassroomService::missing);
        if(!m.status().equals("ACTIVE")||!mapper.activeAccount(target).orElse(false)||mapper.pending(id).isPresent()) throw conflict();
        var t=new Transfer(UUID.randomUUID().toString(),id,user,target,request,"PENDING");mapper.insertTransfer(t);bump(room);return t;
    }
    public Transfer transferAction(long user,String id,String transferId,String action,long version) {
        uuid(transferId);var room=lock(user,id);var t=mapper.transfer(id,transferId).orElseThrow(ClassroomService::missing);
        boolean accept=action.equals("accept");if(!accept&&!action.equals("withdraw")) throw missing();
        if(user!=(accept?t.targetUserId():t.fromUserId())) throw missing();
        String terminal=accept?"ACCEPTED":"WITHDRAWN";
        if(t.status().equals(terminal)) return t;
        if(!t.status().equals("PENDING")) throw conflict();
        effective(user,room);expected(room,version);active(room);
        if(room.ownerId()!=t.fromUserId()) throw conflict();
        var target=mapper.member(id,t.targetUserId()).orElseThrow(ClassroomService::missing);
        if(!target.status().equals("ACTIVE")) throw conflict();
        if(accept) {
            mapper.changeMember(id,t.fromUserId(),"ASSISTANT","ACTIVE");mapper.changeMember(id,t.targetUserId(),"OWNER","ACTIVE");
            bump(new Room(id,t.targetUserId(),room.title(),room.status(),room.inviteSha256(),room.inviteEnabled(),room.version()));
        } else bump(room);
        if(mapper.close(t.id(),terminal)!=1) throw conflict();
        return new Transfer(t.id(),id,t.fromUserId(),t.targetUserId(),t.clientRequestId(),terminal);
    }
    public void lifecycle(long user,String id,String action,long version) {
        var room=owner(user,id,version,true);
        if(action.equals("archive")) {
            active(room);withdrawPending(id,null);
            bump(new Room(id,room.ownerId(),room.title(),"ARCHIVED",room.inviteSha256(),room.inviteEnabled(),room.version()));
        } else if(action.equals("restore")) {
            if(!room.status().equals("ARCHIVED")) throw conflict();
            mapper.changeMember(id,user,"OWNER","ACTIVE");
            bump(new Room(id,room.ownerId(),room.title(),"ACTIVE",room.inviteSha256(),room.inviteEnabled(),room.version()));
        } else throw missing();
    }
    public void delete(long user,String id,long version) {
        var room=owner(user,id,version,true);
        if(mapper.memberCount(id)!=1||mapper.transferCount(id)!=0) throw conflict();
        mapper.deleteMembers(id);mapper.delete(id);
    }
    private Room lock(long user,String id) {uuid(id);accounts.requireCurrentWrite(user);return mapper.lock(id).orElseThrow(ClassroomService::missing);}
    private Room owner(long user,String id,long version,boolean archivedManagement) {
        var room=lock(user,id);
        if(!(archivedManagement&&room.status().equals("ARCHIVED")&&room.ownerId()==user)) effective(user,room);
        if(room.ownerId()!=user) throw forbidden();expected(room,version);if(!archivedManagement) active(room);return room;
    }
    private Member effective(long user,Room room) {return mapper.member(room.id(),user).filter(m->m.status().equals("ACTIVE")).orElseThrow(ClassroomService::missing);}
    private void withdrawPending(String id,Long target) {mapper.pending(id).filter(t->target==null||t.targetUserId()==target||t.fromUserId()==target).ifPresent(t->{if(mapper.close(t.id(),"WITHDRAWN")!=1) throw conflict();});}
    private void bump(Room room) {if(room.version()>=MAX_VERSION||mapper.update(room)!=1) throw conflict();}
    private static void expected(Room room,long version) {if(version<1||version>MAX_VERSION) throw bad();if(room.version()!=version||version==MAX_VERSION) throw conflict();}
    private static void active(Room room) {if(!room.status().equals("ACTIVE")) throw conflict();}
    private static String title(String title) {if(title==null) throw bad();title=title.strip();if(title.isEmpty()||title.codePointCount(0,title.length())>64||title.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xd800&&c<=0xdfff)) throw bad();return title;}
    static void uuid(String value) {if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw missing();}
    private static void requestId(String value) {try {uuid(value);} catch(ResponseStatusException e) {throw bad();}}
    private static String digest(String value) {try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static ResponseStatusException bad() {return new ResponseStatusException(HttpStatus.BAD_REQUEST);}
    static ResponseStatusException missing() {return new ResponseStatusException(HttpStatus.NOT_FOUND);}
    static ResponseStatusException conflict() {return new ResponseStatusException(HttpStatus.CONFLICT);}
    static ResponseStatusException forbidden() {return new ResponseStatusException(HttpStatus.FORBIDDEN);}
}
