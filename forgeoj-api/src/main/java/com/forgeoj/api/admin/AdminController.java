/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.time.LocalDateTime;
import java.util.Locale;
import jakarta.servlet.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService service;private final AdminJwt jwt;private final AdminCookies cookies;private final AdminCsrfRepository csrf;
    public AdminController(AdminService service,AdminJwt jwt,AdminCookies cookies,AdminCsrfRepository csrf){this.service=service;this.jwt=jwt;this.cookies=cookies;this.csrf=csrf;}
    public record User(long id,String username,String role,boolean mustChangePassword) {}
    public record Csrf(String headerName,String parameterName,String token) {}
    public record Session(boolean authenticated,User admin,Csrf csrf) {}
    public record LoginRequest(String username,String password) {}
    public record PasswordRequest(String currentPassword,String password) {}
    public record CreateRequest(String clientRequestId,String username,String role,String password,String reason) {}
    public record MutationRequest(long expectedVersion,String role,String password,String reason) {}
    private Session response(AdminPrincipal p,CsrfToken token){return new Session(p!=null,p==null?null:new User(p.id(),p.username(),p.role(),p.mustChangePassword()),token==null?null:new Csrf(token.getHeaderName(),token.getParameterName(),token.getToken()));}
    @GetMapping("/auth/session") Session session(Authentication auth,CsrfToken token){return response(auth!=null&&auth.getPrincipal() instanceof AdminPrincipal p?p:null,token);}
    private Session logged(AdminService.Login login,HttpServletRequest r,HttpServletResponse s,boolean rotate){cookies.login(s,login,jwt.issue(login));var a=login.account();return response(new AdminPrincipal(a.id(),a.username(),a.role(),a.mustChangePassword(),login.sessionId()),rotate?csrf.rotate(r,s):csrf.loadToken(r));}
    @PostMapping("/auth/login") Session login(@RequestBody LoginRequest input,HttpServletRequest r,HttpServletResponse s){service.rate("login-ip:"+r.getRemoteAddr(),30,300);String name=input.username()==null?"":input.username();service.rate("login-id:"+AdminInput.digest(name.length()>256?"invalid":name.toLowerCase(Locale.ROOT)),10,300);return logged(service.login(input.username(),input.password()),r,s,true);}
    @PostMapping("/auth/refresh") Session refresh(HttpServletRequest r,HttpServletResponse s){service.rate("refresh-ip:"+r.getRemoteAddr(),60,300);return logged(service.refresh(AdminCookies.read(r,AdminCookies.REFRESH)),r,s,false);}
    @PostMapping("/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT) void logout(HttpServletRequest r,HttpServletResponse s){service.rate("logout-ip:"+r.getRemoteAddr(),60,300);service.logoutCurrent(AdminCookies.read(r,AdminCookies.REFRESH));cookies.clear(s);csrf.rotate(r,s);}
    @PostMapping("/auth/logout-all") @ResponseStatus(HttpStatus.NO_CONTENT) void logoutAll(HttpServletRequest r,HttpServletResponse s){limit();service.logoutAll();cookies.clear(s);csrf.rotate(r,s);}
    @PostMapping("/auth/password/change") @ResponseStatus(HttpStatus.NO_CONTENT) void change(@RequestBody PasswordRequest input,HttpServletRequest r,HttpServletResponse s){limit();service.changePassword(input.currentPassword(),input.password());cookies.clear(s);csrf.rotate(r,s);}
    @GetMapping("/accounts") AdminService.Page<AdminMapper.Summary> accounts(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){limit();return service.accounts(page,size);}
    @PostMapping("/accounts") @ResponseStatus(HttpStatus.CREATED) AdminMapper.Summary create(@RequestBody CreateRequest input){limit();return service.create(input.clientRequestId(),input.username(),input.role(),input.password(),input.reason());}
    @PutMapping("/accounts/{id}/role") AdminMapper.Summary role(@PathVariable String id,@RequestBody MutationRequest input){limit();return service.mutate(id(id),input.expectedVersion(),"ROLE",input.role(),null,input.reason());}
    @PostMapping("/accounts/{id}/disable") AdminMapper.Summary disable(@PathVariable String id,@RequestBody MutationRequest input){return mutation(id,"DISABLE",input);}
    @PostMapping("/accounts/{id}/restore") AdminMapper.Summary restore(@PathVariable String id,@RequestBody MutationRequest input){return mutation(id,"RESTORE",input);}
    @PostMapping("/accounts/{id}/password/reset") @ResponseStatus(HttpStatus.NO_CONTENT) void reset(@PathVariable String id,@RequestBody MutationRequest input){mutation(id,"RESET_PASSWORD",input);}
    private AdminMapper.Summary mutation(String id,String action,MutationRequest input){limit();return service.mutate(id(id),input.expectedVersion(),action,null,input.password(),input.reason());}
    @GetMapping("/audit-events") AdminService.Page<AdminMapper.Event> events(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String action,@RequestParam(required=false)Long actorId,@RequestParam(required=false)String targetId,@RequestParam(required=false)LocalDateTime from,@RequestParam(required=false)LocalDateTime to){limit();return service.events(page,size,action,actorId,targetId,from,to);}
    private void limit(){var a=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();if(a!=null&&a.getPrincipal() instanceof AdminPrincipal p)service.rate("actor:"+p.id(),30,300);}
    private long id(String s){try{if(s==null||!s.matches("[1-9][0-9]{0,15}"))throw AdminInput.error(404);return Long.parseLong(s);}catch(NumberFormatException e){throw AdminInput.error(404);}}
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<Void> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<Void> rejected(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();}
}
