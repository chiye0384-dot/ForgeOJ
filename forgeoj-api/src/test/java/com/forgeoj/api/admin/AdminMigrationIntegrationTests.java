/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.*;

@Testcontainers
class AdminMigrationIntegrationTests {
    @Container static final MySQLContainer MYSQL=new com.forgeoj.api.testinfra.DirectMySQLContainer(DockerImageName.parse("container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql")).withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret").withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql").withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-admin-maintenance-fixture.sql"),"/docker-entrypoint-initdb.d/02-maintenance.sql");
    @Test void v17UpgradePreservesEveryOldTableAndConcurrentBootstrapHasOneWinner()throws Exception{
        // 17 versioned migrations + three repeatable seeds; the search seed is a no-op before V24.
        var old=flyway("17");assertThat(old.migrate().migrationsExecuted).isEqualTo(20);var db=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success=TRUE",Integer.class)).isEqualTo(17);
        db.update("INSERT INTO personal_problem_list(id,owner_id,title) VALUES(?,1,'historical original list')",UUID.randomUUID().toString());db.update("INSERT INTO login_session(id,user_id,expires_at) VALUES(?,1,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY))",UUID.randomUUID().toString());
        var before=facts(db);var upgrade=flyway("18");assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);assertThat(facts(db)).isEqualTo(before);assertThat(upgrade.migrate().migrationsExecuted).isZero();assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
        for(String table:List.of("admin_account","admin_login_session","admin_refresh_token","admin_creation_request","admin_audit_event"))assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();assertThat(db.queryForObject("SELECT bootstrapped FROM admin_policy_fence",Boolean.class)).isFalse();
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{var first=pool.submit(()->init(start,"first_super"));var second=pool.submit(()->init(start,"second_super"));start.countDown();assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}
        finally{pool.shutdownNow();}
        assertThat(db.queryForObject("SELECT COUNT(*) FROM admin_account WHERE role='SUPER_ADMIN' AND status='ACTIVE' AND must_change_password=TRUE",Integer.class)).isEqualTo(1);assertThat(db.queryForObject("SELECT COUNT(*) FROM admin_audit_event WHERE action='ADMIN_BOOTSTRAP'",Integer.class)).isEqualTo(1);assertThat(db.queryForObject("SELECT bootstrapped FROM admin_policy_fence",Boolean.class)).isTrue();assertThat(facts(db)).isEqualTo(before);
        db.execute("GRANT SELECT ON forgeoj.flyway_schema_history TO 'limited_maintenance'@'%'");db.execute("GRANT SELECT,UPDATE ON forgeoj.admin_policy_fence TO 'limited_maintenance'@'%'");db.execute("GRANT SELECT,UPDATE ON forgeoj.admin_account TO 'limited_maintenance'@'%'");db.execute("GRANT SELECT,UPDATE ON forgeoj.admin_login_session TO 'limited_maintenance'@'%'");
        var original=db.queryForList("SELECT * FROM admin_account");long id=db.queryForObject("SELECT id FROM admin_account",Long.class);
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"limited_maintenance","public-limited-cli-fixture")){assertThatThrownBy(()->AdminProvisioning.recover(c,id,"new-public-recovery-fixture","audit insert must fail",new BCryptPasswordEncoder())).isInstanceOf(SQLException.class);}
        assertThat(db.queryForList("SELECT * FROM admin_account")).isEqualTo(original);assertThat(facts(db)).isEqualTo(before);
    }
    private boolean init(CountDownLatch start,String name)throws Exception{start.await();try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret")){try(var s=c.createStatement()){s.execute("SET time_zone='+08:00'");}try{AdminProvisioning.bootstrap(c,name,"public-bootstrap-race-fixture",new BCryptPasswordEncoder());try(var s=c.createStatement();var r=s.executeQuery("SELECT ABS(TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP(6))) FROM admin_account")){r.next();assertThat(r.getInt(1)).isLessThan(30);}return true;}catch(SQLException rejected){return false;}}}
    private Flyway flyway(String target){return Flyway.configure().dataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret").locations("classpath:db/migration","classpath:db/devdata").target(target).load();}
    private Map<String,List<String>> facts(JdbcTemplate db){var result=new TreeMap<String,List<String>>();for(String table:db.queryForList("SHOW TABLES",String.class)){if(table.startsWith("admin_")||table.equals("flyway_schema_history"))continue;result.put(table,db.queryForList("SELECT * FROM "+table).stream().map(row->{var normalized=new TreeMap<String,Object>();row.forEach((k,v)->normalized.put(k,v instanceof byte[] b?HexFormat.of().formatHex(b):v));return normalized.toString();}).sorted().toList());}return result;}
}
