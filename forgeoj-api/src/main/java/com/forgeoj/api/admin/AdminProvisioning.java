/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.sql.*;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Explicit local maintenance connection only. Never registered in the Web application. */
public final class AdminProvisioning {
    private AdminProvisioning() {}
    public static long bootstrap(Connection c,String username,String password,PasswordEncoder encoder)throws SQLException{
        String name=AdminInput.username(username);AdminInput.password(password);String hash=encoder.encode(password);
        return transaction(c,()->{
            fence(c);try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM admin_account")){r.next();if(r.getLong(1)!=0)throw new SQLException("Initialization refused");}
            try(var s=c.createStatement();var r=s.executeQuery("SELECT bootstrapped FROM admin_policy_fence WHERE id=1")){r.next();if(r.getBoolean(1))throw new SQLException("Initialization refused");}
            long id;try(var s=c.prepareStatement("INSERT INTO admin_account(username,password_hash,status,role,must_change_password,created_at,updated_at) VALUES(?,?,'ACTIVE','SUPER_ADMIN',TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",Statement.RETURN_GENERATED_KEYS)){s.setString(1,name);s.setString(2,hash);s.executeUpdate();try(var r=s.getGeneratedKeys()){r.next();id=r.getLong(1);}}
            try(var s=c.createStatement()){if(s.executeUpdate("UPDATE admin_policy_fence SET bootstrapped=TRUE WHERE id=1 AND bootstrapped=FALSE")!=1)throw new SQLException("Initialization refused");}
            event(c,"BOOTSTRAP","ADMIN_BOOTSTRAP",id,"explicit first administrator",null,"status=ACTIVE;role=SUPER_ADMIN;mustChangePassword=true;version=1");return id;
        });
    }
    public static long recover(Connection c,long id,String password,String reason,PasswordEncoder encoder)throws SQLException{
        AdminInput.password(password);String why=AdminInput.reason(reason),hash=encoder.encode(password);if(id<1||id>AdminInput.MAX_VERSION)throw new SQLException("Recovery refused");
        return transaction(c,()->{
            fence(c);String before;long version;try(var s=c.prepareStatement("SELECT status,role,must_change_password,version,password_hash FROM admin_account WHERE id=? FOR UPDATE")){s.setLong(1,id);try(var r=s.executeQuery()){if(!r.next()||!"SUPER_ADMIN".equals(r.getString(2)))throw new SQLException("Recovery refused");if(encoder.matches(password,r.getString(5)))throw new SQLException("New password required");version=r.getLong(4);if(version>=AdminInput.MAX_VERSION)throw new SQLException("Recovery refused");before="status="+r.getString(1)+";role=SUPER_ADMIN;mustChangePassword="+r.getBoolean(3)+";version="+version;}}
            try(var s=c.prepareStatement("UPDATE admin_account SET status='ACTIVE',password_hash=?,must_change_password=TRUE,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=?")){s.setString(1,hash);s.setLong(2,id);s.executeUpdate();}
            try(var s=c.prepareStatement("UPDATE admin_login_session SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(6)) WHERE admin_id=?")){s.setLong(1,id);s.executeUpdate();}
            event(c,"LOCAL_RECOVERY","ADMIN_RECOVER",id,why,before,"status=ACTIVE;role=SUPER_ADMIN;mustChangePassword=true;version="+(version+1));return id;
        });
    }
    private static void fence(Connection c)throws SQLException{
        try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version='18'")){r.next();if(r.getInt(1)!=1)throw new SQLException("Apply V18 first");}
        try(var s=c.createStatement();var r=s.executeQuery("SELECT id FROM admin_policy_fence WHERE id=1 FOR UPDATE")){if(!r.next())throw new SQLException("Initialization unavailable");}
    }
    private static void event(Connection c,String type,String action,long target,String reason,String before,String after)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO admin_audit_event(id,occurred_at,actor_type,action,target_type,target_id,outcome,reason,before_state,after_state,correlation_id) VALUES(?,UTC_TIMESTAMP(6),?,?,'ADMIN_ACCOUNT',?,'SUCCESS',?,?,?,?)")){s.setString(1,UUID.randomUUID().toString());s.setString(2,type);s.setString(3,action);s.setString(4,Long.toString(target));s.setString(5,reason);s.setString(6,before);s.setString(7,after);s.setString(8,UUID.randomUUID().toString());s.executeUpdate();}
    }
    @FunctionalInterface private interface Operation{long run()throws SQLException;}
    private static long transaction(Connection c,Operation operation)throws SQLException{
        if(!c.getAutoCommit())throw new SQLException("Dedicated maintenance connection required");int isolation=c.getTransactionIsolation();c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
        try{long id=operation.run();c.commit();return id;}catch(SQLException|RuntimeException failure){c.rollback();throw failure;}finally{c.setAutoCommit(true);c.setTransactionIsolation(isolation);}
    }
}
