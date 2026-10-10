/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.search;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.*;
@Testcontainers
class SearchMigrationIntegrationTests {
    @Container static final MySQLContainer MYSQL=new com.forgeoj.api.testinfra.DirectMySQLContainer(DockerImageName.parse("container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql"))
        .withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret").withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    Flyway flyway(String target){return Flyway.configure().dataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret").locations("classpath:db/migration").target(target).load();}
    @Test void v23UpgradePreservesEveryExistingTableAndInitializesOnlyPublicVersions()throws Exception {
        assertThat(flyway("23").migrate().migrationsExecuted).isEqualTo(23);
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret");var db=new JdbcTemplate(source);
        try(var connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/devdata/R__seed_m0_development_data.sql"));ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/devdata/R__seed_m2_library_metadata.sql"));}
        String classroom=UUID.randomUUID().toString();db.update("INSERT INTO classroom(id,owner_id,title) VALUES(?,1,'upgrade fixture classroom')",classroom);
        db.update("INSERT INTO problem(id,slug,title,statement_text,input_description,output_description,public_samples_json,status,scope,classroom_id) VALUES(101,'search-upgrade-private','PRIVATE_TITLE','PRIVATE_BODY','','',JSON_ARRAY(),'ACTIVE','CLASSROOM',?)",classroom);
        db.update("INSERT INTO problem(id,slug,title,statement_text,input_description,output_description,public_samples_json,status) VALUES(102,'search-upgrade-archived','archived title','archived body','','',JSON_ARRAY(),'ARCHIVED')");
        var tables=db.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='forgeoj' AND TABLE_NAME<>'flyway_schema_history' ORDER BY TABLE_NAME",String.class);
        var before=facts(db,tables);var upgrade=flyway("24");assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);assertThat(facts(db,tables)).isEqualTo(before);
        assertThat(db.queryForList("SELECT problem_id FROM public_search_version ORDER BY problem_id",Long.class)).containsExactly(1L,102L);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM public_search_outbox WHERE data_version=1",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM public_search_version WHERE problem_id=101",Integer.class)).isZero();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();assertThat(facts(db,tables)).isEqualTo(before);
        assertThatThrownBy(()->db.update("INSERT INTO public_search_version(problem_id,data_version) VALUES(101,1)")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("UPDATE public_search_version SET data_version=0 WHERE problem_id=1")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("UPDATE public_search_version SET data_version=9007199254740992 WHERE problem_id=1")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("INSERT INTO public_search_outbox(id,problem_id,data_version,public_epoch) VALUES(?,1,1,1)",UUID.randomUUID().toString())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    Map<String,List<Map<String,Object>>> facts(JdbcTemplate db,List<String> tables){
        var result=new TreeMap<String,List<Map<String,Object>>>();for(String table:tables){if(!table.matches("[a-z_]+"))throw new IllegalStateException("Unexpected fixture table");
            result.put(table,db.queryForList("SELECT * FROM "+table+" ORDER BY 1").stream().map(row->{Map<String,Object> values=new TreeMap<>();row.forEach((key,value)->values.put(key,value instanceof byte[] bytes?HexFormat.of().formatHex(bytes):value));return values;}).toList());
        }return result;
    }
}
