/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.search;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.*;
import com.forgeoj.api.cache.*;

@Testcontainers
@SpringBootTest(properties={"spring.flyway.locations=classpath:db/migration,classpath:db/devdata",
    "forgeoj.assignments.scheduler.enabled=false","spring.rabbitmq.listener.simple.auto-startup=false"})
class SearchProjectionIntegrationTests {
    @Container static final MySQLContainer MYSQL=new com.forgeoj.api.testinfra.DirectMySQLContainer(
        DockerImageName.parse("container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql"))
        .withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret")
        .withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",()->"forgeoj_api");r.add("spring.datasource.password",()->"m0-api-test-secret");
        r.add("spring.flyway.url",MYSQL::getJdbcUrl);r.add("spring.flyway.user",()->"forgeoj_migrator");r.add("spring.flyway.password",()->"m0-migrator-test-secret");
    }
    @Autowired SearchChanges changes;
    @Autowired SearchMapper mapper;
    @Autowired CacheInvalidations invalidations;
    @Autowired CacheMapper epochs;
    @Autowired PlatformTransactionManager manager;
    @Autowired javax.sql.DataSource apiSource;
    JdbcTemplate migrator(){return db("forgeoj_migrator","m0-migrator-test-secret");}
    JdbcTemplate db(String user,String password){return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),user,password));}
    void change(){new TransactionTemplate(manager).executeWithoutResult(s->{invalidations.publicChanged();changes.changed(1);});}
    @Test void commitsOneVersionAndMinimalEventWhileRollbackPreservesAllThreeFacts(){
        long old=mapper.projection(1).orElseThrow().dataVersion(),count=mapper.eventCount(1),epoch=epochs.publicRevision();
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(s->{invalidations.publicChanged();changes.changed(1);throw new IllegalStateException("fixture rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(mapper.projection(1).orElseThrow().dataVersion()).isEqualTo(old);
        assertThat(mapper.eventCount(1)).isEqualTo(count);assertThat(epochs.publicRevision()).isEqualTo(epoch);
        change();assertThat(mapper.projection(1).orElseThrow().dataVersion()).isEqualTo(old+1);
        assertThat(mapper.eventCount(1)).isEqualTo(count+1);assertThat(epochs.publicRevision()).isEqualTo(epoch+1);
        assertThat(migrator().queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='forgeoj' AND TABLE_NAME='public_search_outbox'",String.class)).doesNotContain("payload","source_code","statement_text","title");
        assertThatThrownBy(()->changes.changed(1)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @Test void concurrentChangesSerializeVersionsAndEvents()throws Exception {
        long before=mapper.projection(1).orElseThrow().dataVersion(),count=mapper.eventCount(1);
        try(var pool=Executors.newFixedThreadPool(4)){
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<8;i++)tasks.add(pool.submit(()->{start.await();change();return null;}));
            start.countDown();for(var task:tasks)task.get(30,TimeUnit.SECONDS);
        }
        assertThat(mapper.projection(1).orElseThrow().dataVersion()).isEqualTo(before+8);
        assertThat(mapper.eventCount(1)).isEqualTo(count+8);
        assertThat(migrator().queryForList("SELECT data_version FROM public_search_outbox WHERE problem_id=1 AND data_version>? ORDER BY data_version",Long.class,before)).containsExactly(before+1,before+2,before+3,before+4,before+5,before+6,before+7,before+8);
    }
    @Test void classroomProjectionCannotBeCreatedEvenByDirectApiSql(){
        String classroom=UUID.randomUUID().toString();var db=migrator();
        db.update("INSERT INTO classroom(id,owner_id,title) VALUES(?,1,'search private fixture')",classroom);
        db.update("INSERT INTO problem(slug,title,statement_text,input_description,output_description,public_samples_json,status,scope,classroom_id) VALUES(?,'private title','private body','','',JSON_ARRAY(),'ACTIVE','CLASSROOM',?)",classroom,classroom);
        long id=db.queryForObject("SELECT id FROM problem WHERE slug=?",Long.class,classroom);
        assertThat(mapper.projection(id)).isEmpty();long epoch=epochs.publicRevision();
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(s->{invalidations.publicChanged();changes.changed(id);})).isInstanceOf(IllegalStateException.class);
        assertThat(epochs.publicRevision()).isEqualTo(epoch);assertThat(mapper.eventCount(id)).isZero();
        var api=new JdbcTemplate(apiSource);
        assertThatThrownBy(()->api.update("INSERT INTO public_search_version(problem_id,data_version) VALUES(?,1)",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->api.update("INSERT INTO public_search_version(problem_id,scope,data_version) VALUES(?,'CLASSROOM',1)",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void workerCannotReadSearchFactsAndApiCannotRewriteBindingsDeleteOrGrant(){
        var worker=db("forgeoj_worker","m0-worker-test-secret");var api=new JdbcTemplate(apiSource);
        for(String table:List.of("public_search_version","public_search_outbox","public_search_delivery","public_search_dead_outbox","public_search_control","public_search_rebuild","public_search_dead_recovery")){
            assertThatThrownBy(()->worker.queryForList("SELECT * FROM "+table)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->api.update("DELETE FROM "+table+" WHERE 1=0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThatThrownBy(()->api.update("UPDATE public_search_version SET scope='CLASSROOM' WHERE problem_id=1")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->api.update("UPDATE public_search_outbox SET data_version=10 WHERE 1=0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->api.execute("GRANT SELECT ON forgeoj.public_search_version TO 'forgeoj_worker'@'%'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
