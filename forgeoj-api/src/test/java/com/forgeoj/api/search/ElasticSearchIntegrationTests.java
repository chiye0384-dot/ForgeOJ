/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.search;
import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.*;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.ExposedPort;
import com.forgeoj.api.cache.*;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@Timeout(60)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.flyway.locations=classpath:db/migration,classpath:db/devdata","forgeoj.search.enabled=true","forgeoj.search.background-enabled=false",
    "forgeoj.search.allow-loopback-http=true","forgeoj.assignments.scheduler.enabled=false","forgeoj.self-test.cleanup.enabled=false","spring.rabbitmq.listener.simple.auto-startup=false"})
class ElasticSearchIntegrationTests {
    static final String IMAGE="docker.elastic.co/elasticsearch/elasticsearch:9.5.3@sha256:f456578fc2a620a8a4f4c21d070fff1f6070345adb2be5e5626b65be72aea350",PASSWORD="public-search-ephemeral-fixture";
    @Container static final MySQLContainer MYSQL=new com.forgeoj.api.testinfra.DirectMySQLContainer(DockerImageName.parse("container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be").asCompatibleSubstituteFor("mysql"))
        .withDatabaseName("forgeoj").withUsername("bootstrap").withPassword("bootstrap-test-secret").withCopyFileToContainer(MountableFile.forClasspathResource("mysql/init-test-users.sql"),"/docker-entrypoint-initdb.d/01-users.sql");
    static final boolean BUILDER_NETWORK=System.getenv("TESTCONTAINERS_HOST_OVERRIDE")!=null;
    @Container static final GenericContainer<?> ES=server();
    @Container static final RabbitMQContainer RABBIT=new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.6-management@sha256:cdf40d8cb363d145e377ed88d59696a42386ffe54b30125f10eb128b862eea95").asCompatibleSubstituteFor("rabbitmq"))
        .withAdminUser("forgeoj").withAdminPassword("search-rabbit-fixture").withEnv("RABBITMQ_DEFAULT_VHOST","/forgeoj");
    static GenericContainer<?> server(){
        var server=new GenericContainer<>(DockerImageName.parse(IMAGE))
        .withEnv("discovery.type","single-node").withEnv("xpack.security.enabled","true").withEnv("xpack.security.autoconfiguration.enabled","false")
        .withEnv("xpack.security.http.ssl.enabled","false").withEnv("xpack.license.self_generated.type","basic").withEnv("xpack.ml.enabled","false")
        .withEnv("ES_JAVA_OPTS","-Xms512m -Xmx512m").withEnv("ELASTIC_PASSWORD",PASSWORD)
        .withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withMemory(1073741824L));
        if(BUILDER_NETWORK){
            String builder=System.getenv("HOSTNAME");if(builder==null||!builder.matches("[a-f0-9]{12,64}"))throw new IllegalStateException("Unknown isolated builder network");
            server.withNetworkMode("container:"+builder).waitingFor(new AbstractWaitStrategy(){@Override protected void waitUntilReady(){
                long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
                while(System.nanoTime()<deadline){try{
                    var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:9200/")).timeout(Duration.ofSeconds(2)).header("Authorization","Basic "+Base64.getEncoder().encodeToString(("elastic:"+PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8))).build();
                    if(HttpClient.newHttpClient().send(r,HttpResponse.BodyHandlers.discarding()).statusCode()==200)return;
                }catch(Exception ignored){}try{Thread.sleep(500);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
                throw new IllegalStateException("ES fixture startup timeout");
            }});
        }else server.withExposedPorts(9200).withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),ExposedPort.tcp(9200))))
            .waitingFor(Wait.forHttp("/").withBasicCredentials("elastic",PASSWORD).forStatusCode(200));
        return server.withStartupTimeout(Duration.ofSeconds(120));
    }
    static String esUrl(){return BUILDER_NETWORK?"http://127.0.0.1:9200":"http://"+ES.getHost()+":"+ES.getMappedPort(9200);}
    static synchronized String runtimeUser(){
        raw("PUT","/_security/role/forgeoj_search",Map.of("cluster",List.of(),"indices",List.of(Map.of("names",List.of("forgeoj-public-*"),"privileges",List.of("manage","read","write","create_index","view_index_metadata")))));
        raw("PUT","/_security/user/forgeoj_search",Map.of("password",PASSWORD,"roles",List.of("forgeoj_search")));return "forgeoj_search";
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",()->"forgeoj_api");r.add("spring.datasource.password",()->"m0-api-test-secret");
        r.add("spring.flyway.url",MYSQL::getJdbcUrl);r.add("spring.flyway.user",()->"forgeoj_migrator");r.add("spring.flyway.password",()->"m0-migrator-test-secret");
        r.add("forgeoj.search.url",ElasticSearchIntegrationTests::esUrl);r.add("forgeoj.search.username",ElasticSearchIntegrationTests::runtimeUser);r.add("forgeoj.search.password",()->PASSWORD);
        r.add("spring.rabbitmq.host",RABBIT::getHost);r.add("spring.rabbitmq.port",RABBIT::getAmqpPort);r.add("spring.rabbitmq.username",()->"forgeoj");r.add("spring.rabbitmq.password",()->"search-rabbit-fixture");r.add("spring.rabbitmq.virtual-host",()->"/forgeoj");
    }
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ElasticSearchClient es;@Autowired SearchMapper mapper;@Autowired PublicSearchService service;@Autowired SearchRebuilder rebuilder;
    @Autowired SearchSynchronizer sync;@Autowired SearchDeliveryMapper deliveries;@Autowired CacheMapper epochs;@Autowired CacheInvalidations invalidations;@Autowired SearchChanges changes;
    @Autowired ObjectMapper json;@Autowired PlatformTransactionManager manager;@LocalServerPort int port;
    @Autowired javax.sql.DataSource apiSource;
    @Autowired org.springframework.amqp.rabbit.core.RabbitAdmin rabbitAdmin;
    @Autowired org.springframework.amqp.rabbit.connection.ConnectionFactory rabbitConnection;
    @Autowired SearchMessageListener listener;
    @Autowired SearchRebuildMapper rebuildJobs;
    String index,job;
    JdbcTemplate db(){return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"forgeoj_migrator","m0-migrator-test-secret"));}
    static tools.jackson.databind.JsonNode raw(String method,String path,Object body){
        try{var json=new ObjectMapper();var request=HttpRequest.newBuilder(URI.create(esUrl()+path)).timeout(Duration.ofSeconds(5)).header("Authorization","Basic "+Base64.getEncoder().encodeToString(("elastic:"+PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8))).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()>=400)throw new IllegalStateException("Fixture ES request failed: "+response.statusCode());return json.readTree(response.body());
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    @BeforeEach void prepare(){
        org.mockito.Mockito.reset(es);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(6)).ignoreExceptions().until(()->{es.optionalAlias();return true;});
        db().update("UPDATE public_search_control SET active_job=NULL WHERE id=1");
        // Disposable metadata setup is included in the new fixture projection recorded below.
        db().update("INSERT IGNORE INTO problem_tag(problem_id,tag) VALUES(1,'Math')");
        change("UPDATE problem SET title='索引公开题',statement_text='火星旅行规划。<script>alert(1)</script> 中文全文检索',status='ACTIVE' WHERE id=1");
        job=UUID.randomUUID().toString();index="forgeoj-public-"+job.replace("-","");es.create(index,job);
        for(var p:mapper.scan(0))es.index(index,p,mapper.tags(p.problemId()));es.refresh(index);
        es.switchAlias(es.optionalAlias().map(ElasticSearchClient.Alias::index).orElse(null),index);
        db().update("UPDATE public_search_control SET index_name=?,index_uuid=?,readable_epoch=? WHERE id=1",index,es.uuid(index),epochs.publicRevision());
    }
    void change(String sql){new TransactionTemplate(manager).executeWithoutResult(s->{new JdbcTemplate(apiSource).update(sql);invalidations.publicChanged();changes.changed(1);});}
    @Test void officialRuntimeIsBasicAndAuthenticatedWithoutPluginsOrUnrelatedIndexPermission()throws Exception {
        assertThat(raw("GET","/",null).path("version").path("number").asText()).isEqualTo("9.5.3");
        assertThat(raw("GET","/_license",null).path("license").path("type").asText()).isEqualTo("basic");
        var request=HttpRequest.newBuilder(URI.create(esUrl()+"/")).build();assertThat(HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        var unauthorized=HttpRequest.newBuilder(URI.create(esUrl()+"/unrelated-private-index/_search")).header("Authorization","Basic "+Base64.getEncoder().encodeToString(("forgeoj_search:"+PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8))).build();
        assertThat(HttpClient.newHttpClient().send(unauthorized,HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        var nodes=raw("GET","/_nodes/plugins",null).path("nodes");for(var n:nodes)assertThat(n.path("plugins").size()).isZero();
    }
    @Test void chineseBodyFiltersPaginationAndTextOnlyHighlightsUseVerifiedMysqlFacts()throws Exception {
        var p=service.search("火星", "EASY",null,1,20);assertThat(p.mode()).isEqualTo("FULL_TEXT");assertThat(p.total()).isEqualTo(1);
        assertThat(p.items().getFirst().title()).isEqualTo("索引公开题");assertThat(p.items().getFirst().highlights().get(1)).contains(new PublicSearchService.Segment("火星",true));
        assertThat(service.search("火星","HARD",null,1,20).total()).isZero();assertThat(service.search("火星",null,"missing-tag",1,20).total()).isZero();assertThat(service.search("火星",null,null,2,1).items()).isEmpty();
        assertThat(service.search("火星",null,"máth",1,20).mode()).isEqualTo("FULL_TEXT");assertThat(service.search("火星",null,"máth",1,20).total()).isEqualTo(1);
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/problems/search?keyword="+URLEncoder.encode("火星",java.nio.charset.StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);var root=json.readTree(response.body());assertThat(root.propertyNames()).containsExactlyInAnyOrder("items","page","size","total","mode","notice");
        assertThat(response.body()).doesNotContain("_source","_score","inputDescription","solutionCode","sourceCode","password");assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }
    @Test void staleContentOrCounterfeitCandidateFallsBackAsAWhole(){
        var p=mapper.projection(1).orElseThrow();var fake=new LinkedHashMap<String,Object>();fake.put("problemId",1);fake.put("dataVersion",p.dataVersion());fake.put("active",true);fake.put("title","private injected title");fake.put("statementText","火星 private injected body");fake.put("difficulty","EASY");fake.put("tags",mapper.tags(1));
        raw("PUT","/"+index+"/_doc/1?version_type=external_gte&version="+p.dataVersion(),fake);es.refresh(index);
        var result=service.search("火星",null,null,1,20);assertThat(result.mode()).isEqualTo("TITLE_FALLBACK");assertThat(result.total()).isZero();assertThat(result.items()).isEmpty();
    }
    @Test void lagArchiveAndRestorationNeverReturnOldBodyAndTombstoneRejectsLateEvents(){
        var before=mapper.projection(1).orElseThrow();change("UPDATE problem SET status='ARCHIVED' WHERE id=1");
        assertThat(service.search("火星",null,null,1,20).mode()).isEqualTo("TITLE_FALLBACK");
        var tombstone=mapper.projection(1).orElseThrow();es.index(index,tombstone,List.of());es.index(index,before,List.of());es.refresh(index);
        assertThat(raw("GET","/"+index+"/_doc/1",null).path("_source").propertyNames()).containsExactlyInAnyOrder("problemId","dataVersion","active");
        assertThat(es.search("火星",null,null)).isEmpty();change("UPDATE problem SET status='ACTIVE' WHERE id=1");
        assertThat(service.search("火星",null,null,1,20).mode()).isEqualTo("TITLE_FALLBACK");
    }
    @Test void repeatAndOutOfOrderWritesAreIdempotentButEqualVersionDifferentTextFails(){
        var p=mapper.projection(1).orElseThrow();es.index(index,p,mapper.tags(1));es.index(index,p,mapper.tags(1));
        change("UPDATE problem SET title='new public title' WHERE id=1");var current=mapper.projection(1).orElseThrow();es.index(index,current,mapper.tags(1));es.index(index,p,mapper.tags(1));
        assertThat(raw("GET","/"+index+"/_doc/1",null).path("_version").asLong()).isEqualTo(current.dataVersion());
        var fake=new SearchMapper.Projection(1,current.dataVersion(),true,current.slug(),"unequal",current.statementText(),current.difficulty(),current.judgeVersion());
        assertThatThrownBy(()->es.index(index,fake,mapper.tags(1))).isInstanceOf(ElasticSearchClient.SearchUnavailable.class);
    }
    @Test void deletedManagedIndexAndAliasMismatchFailClosed(){
        raw("DELETE","/"+index,null);
        assertThat(service.search("索引",null,null,1,20).mode()).isEqualTo("TITLE_FALLBACK");
        assertThat(service.search("索引",null,null,1,20).items()).hasSize(1);
    }
    @Test void boundedConsumerAttemptsAndCommittedTerminalStatusControlAck(){
        String id=db().queryForObject("SELECT id FROM public_search_outbox WHERE problem_id=1 ORDER BY data_version DESC LIMIT 1",String.class);
        sync.receive(id);assertThat(sync.ackReady(id)).isFalse();sync.process(id);assertThat(sync.ackReady(id)).isTrue();sync.process(id);
        assertThat(db().queryForObject("SELECT attempts FROM public_search_delivery WHERE event_id=?",Integer.class,id)).isEqualTo(1);
    }
    record Browser(HttpClient client,tools.jackson.databind.JsonNode session) {}
    Browser login(String role)throws Exception {
        String username="search_"+role.toLowerCase(Locale.ROOT);
        db().update("INSERT INTO admin_account(username,password_hash,status,role,must_change_password,created_at,updated_at) VALUES(?,?,'ACTIVE',?,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE status='ACTIVE',role=VALUES(role),must_change_password=FALSE",username,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(PASSWORD),role);
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var start=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/admin/auth/session")).build(),HttpResponse.BodyHandlers.ofString());
        var browser=new Browser(client,json.readTree(start.body()));var response=request(browser,"POST","/auth/login",Map.of("username",username,"password",PASSWORD));assertThat(response.statusCode()).isEqualTo(200);return new Browser(client,json.readTree(response.body()));
    }
    HttpResponse<String> request(Browser browser,String method,String path,Object body)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/admin"+path)).header("Origin","http://localhost:"+port);
        if(body!=null)builder.header("Content-Type","application/json").header(browser.session().path("csrf").path("headerName").asText(),browser.session().path("csrf").path("token").asText());
        return browser.client().send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    Map<String,Object> input(long version,String id,String reason){return Map.of("expectedVersion",version,"clientRequestId",id,"reason",reason);}
    @Test void rebuildIsAuditedIdempotentAndRunsOutsideTheManagementRequest()throws Exception {
        var ops=login("OPS_ADMIN");long version=mapper.control().version();var body=input(version,UUID.randomUUID().toString(),"搜索重建验收");
        var created=request(ops,"POST","/search/rebuilds",body);assertThat(created.statusCode()).isEqualTo(200);String id=json.readTree(created.body()).path("id").asText();
        assertThat(json.readTree(created.body()).path("status").asText()).isEqualTo("QUEUED");
        assertThat(request(ops,"POST","/search/rebuilds",body).body()).isEqualTo(created.body());
        assertThat(request(ops,"POST","/search/rebuilds",input(version,(String)body.get("clientRequestId"),"different")).statusCode()).isEqualTo(409);
        assertThat(request(ops,"POST","/search/rebuilds",input(mapper.control().version(),UUID.randomUUID().toString(),"second concurrent rebuild")).statusCode()).isEqualTo(409);
        assertThat(service.search("火星",null,null,1,20).mode()).isEqualTo("TITLE_FALLBACK");
        rebuilder.run();assertThat(db().queryForObject("SELECT status FROM public_search_rebuild WHERE id=?",String.class,id)).isEqualTo("SUCCEEDED");
        assertThat(service.search("火星",null,null,1,20).mode()).isEqualTo("FULL_TEXT");
        assertThat(db().queryForObject("SELECT COUNT(*) FROM admin_audit_event WHERE target_id=? AND action='SEARCH_REBUILD_QUEUED'",Integer.class,id)).isEqualTo(1);
        assertThat(db().queryForObject("SELECT COUNT(*) FROM admin_audit_event WHERE target_id=? AND action='SEARCH_REBUILD_SUCCEEDED'",Integer.class,id)).isEqualTo(1);
        var history=request(ops,"GET","/search/rebuilds",null);assertThat(history.statusCode()).isEqualTo(200);assertThat(history.body()).doesNotContain("targetIndex","targetUuid","requestSha256","leaseToken","statementText","sourceCode");
    }
    @Test void currentRoleRevocationAndFirstPasswordBoundaryProtectSearchManagement()throws Exception {
        var reviewer=login("CONTENT_REVIEWER");assertThat(request(reviewer,"GET","/search/status",null).statusCode()).isEqualTo(403);
        var ops=login("OPS_ADMIN");assertThat(request(ops,"GET","/search/status",null).statusCode()).isEqualTo(200);
        db().update("UPDATE admin_account SET role='CONTENT_REVIEWER',version=version+1 WHERE username='search_ops_admin'");
        assertThat(request(ops,"GET","/search/status",null).statusCode()).isEqualTo(403);
        assertThat(request(ops,"POST","/search/rebuilds",input(mapper.control().version(),UUID.randomUUID().toString(),"revoked")).statusCode()).isEqualTo(403);
        var root=login("SUPER_ADMIN");db().update("UPDATE admin_account SET must_change_password=TRUE,version=version+1 WHERE username='search_super_admin'");
        assertThat(request(root,"GET","/search/status",null).statusCode()).isEqualTo(403);
    }
    @Test void managedIndexClearingFallsBackAndACompleteRebuildRestoresTheBodyQuery()throws Exception {
        raw("POST","/"+index+"/_delete_by_query?refresh=true",Map.of("query",Map.of("match_all",Map.of())));
        assertThat(service.search("火星",null,null,1,20).mode()).isEqualTo("TITLE_FALLBACK");
        var ops=login("OPS_ADMIN");assertThat(request(ops,"POST","/search/rebuilds",input(mapper.control().version(),UUID.randomUUID().toString(),"重建已清空的本测试索引")).statusCode()).isEqualTo(200);
        rebuilder.run();assertThat(service.search("火星",null,null,1,20).mode()).isEqualTo("FULL_TEXT");
    }
    @Test void realRabbitPersistentFourFieldMessageIsAckedAfterMysqlTerminalCommit()throws Exception {
        rabbitAdmin.purgeQueue(SearchRabbitConfig.QUEUE);rabbitAdmin.purgeQueue(SearchRabbitConfig.DEAD);sync.publish();
        var connection=rabbitConnection.createConnection();var channel=connection.createChannel(false);int seen=0;
        try{
            com.rabbitmq.client.GetResponse delivery;
            while((delivery=channel.basicGet(SearchRabbitConfig.QUEUE,false))!=null){
                var body=json.readTree(delivery.getBody());assertThat(body.propertyNames()).containsExactlyInAnyOrder("eventId","problemId","dataVersion","type");assertThat(delivery.getProps().getDeliveryMode()).isEqualTo(2);
                var properties=new org.springframework.amqp.core.MessageProperties();properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
                var observed=org.mockito.Mockito.mock(com.rabbitmq.client.Channel.class,org.mockito.AdditionalAnswers.delegatesTo(channel));String id=body.path("eventId").asText();
                org.mockito.Mockito.doAnswer(call->{assertThat(sync.ackReady(id)).isTrue();channel.basicAck(call.getArgument(0),call.getArgument(1));return null;}).when(observed).basicAck(delivery.getEnvelope().getDeliveryTag(),false);
                listener.handle(new org.springframework.amqp.core.Message(delivery.getBody(),properties),observed);seen++;
            }
            assertThat(seen).isGreaterThan(0);assertThat(channel.queueDeclarePassive(SearchRabbitConfig.QUEUE).getMessageCount()).isZero();
        }finally{channel.close();connection.close();}
    }
    @Test void pausedEsFallsBackAndFiveIndexFailuresPersistDeadLetterBeforeAck()throws Exception {
        change("UPDATE problem SET title='故障期间公开标题' WHERE id=1");String id=db().queryForObject("SELECT id FROM public_search_outbox WHERE problem_id=1 ORDER BY data_version DESC LIMIT 1",String.class);
        sync.receive(id);var docker=org.testcontainers.DockerClientFactory.instance().client();String owned=ES.getContainerId();docker.pauseContainerCmd(owned).exec();
        try{
            assertThat(service.search("故障",null,null,1,20).mode()).isEqualTo("TITLE_FALLBACK");assertThat(service.search("故障",null,null,1,20).items()).hasSize(1);
            for(int attempt=1;attempt<=5;attempt++){
                db().update("UPDATE public_search_delivery SET next_attempt_at=UTC_TIMESTAMP(6) WHERE event_id=?",id);sync.process(id);
                assertThat(db().queryForObject("SELECT attempts FROM public_search_delivery WHERE event_id=?",Integer.class,id)).isEqualTo(attempt);
            }
            assertThat(deliveries.status(id)).contains("DEAD_LETTER");assertThat(sync.ackReady(id)).isFalse();sync.publish();assertThat(sync.ackReady(id)).isTrue();sync.process(id);
            assertThat(db().queryForObject("SELECT attempts FROM public_search_delivery WHERE event_id=?",Integer.class,id)).isEqualTo(5);
        }finally{docker.unpauseContainerCmd(owned).exec();}
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(6)).ignoreExceptions().until(()->{es.optionalAlias();return true;});
        var ops=login("OPS_ADMIN");assertThat(request(ops,"POST","/search/rebuilds",input(mapper.control().version(),UUID.randomUUID().toString(),"恢复死信覆盖的公开搜索投影")).statusCode()).isEqualTo(200);
        rebuilder.run();assertThat(service.search("火星",null,null,1,20).mode()).isEqualTo("FULL_TEXT");assertThat(deliveries.status(id)).contains("DEAD_LETTER");
    }
    @Test void unroutablePublisherStopsAtFiveAndRetainsFailedFacts()throws Exception {
        rabbitAdmin.getQueueInfo(SearchRabbitConfig.QUEUE);
        var binding=new org.springframework.amqp.core.Binding(SearchRabbitConfig.QUEUE,org.springframework.amqp.core.Binding.DestinationType.QUEUE,SearchRabbitConfig.EXCHANGE,SearchRabbitConfig.ROUTE,null);
        rabbitAdmin.removeBinding(binding);String id=db().queryForObject("SELECT id FROM public_search_outbox WHERE problem_id=1 ORDER BY data_version DESC LIMIT 1",String.class);
        try{
            for(int attempt=1;attempt<=5;attempt++){
                db().update("UPDATE public_search_outbox SET next_publish_at=UTC_TIMESTAMP(6) WHERE id=?",id);sync.publish();
                assertThat(db().queryForObject("SELECT publish_attempts FROM public_search_outbox WHERE id=?",Integer.class,id)).isEqualTo(attempt);
            }
            assertThat(db().queryForObject("SELECT failed_at IS NOT NULL AND published_at IS NULL FROM public_search_outbox WHERE id=?",Boolean.class,id)).isTrue();sync.publish();
            assertThat(db().queryForObject("SELECT publish_attempts FROM public_search_outbox WHERE id=?",Integer.class,id)).isEqualTo(5);
        }finally{rabbitAdmin.declareBinding(binding);}
    }
    @Test void exhaustedDeadPublicationNeedsANewAuditedRebuildAndConfirmedRecovery()throws Exception {
        String id=db().queryForObject("SELECT id FROM public_search_outbox WHERE problem_id=1 ORDER BY data_version DESC LIMIT 1",String.class);
        sync.receive(id);
        // Only the disposable fixture simulates an already committed exhausted consumer.
        db().update("UPDATE public_search_delivery SET status='DEAD_LETTER',attempts=5,finished_at=UTC_TIMESTAMP(6) WHERE event_id=?",id);
        deliveries.dead(id);rabbitAdmin.purgeQueue(SearchRabbitConfig.DEAD);
        var binding=new org.springframework.amqp.core.Binding(SearchRabbitConfig.DEAD,org.springframework.amqp.core.Binding.DestinationType.QUEUE,SearchRabbitConfig.EXCHANGE,SearchRabbitConfig.DEAD_ROUTE,null);
        rabbitAdmin.removeBinding(binding);
        var ops=login("OPS_ADMIN");String first;
        try{
            for(int attempt=1;attempt<=5;attempt++){
                db().update("UPDATE public_search_dead_outbox SET next_attempt_at=UTC_TIMESTAMP(6) WHERE event_id=?",id);sync.publish();
                assertThat(db().queryForObject("SELECT attempts FROM public_search_dead_outbox WHERE event_id=?",Integer.class,id)).isEqualTo(attempt);
            }
            assertThat(sync.ackReady(id)).isFalse();assertThat(deliveries.blockedDeadLetters()).isPositive();
            var body=input(mapper.control().version(),UUID.randomUUID().toString(),"补发已耗尽的搜索死信");
            var result=request(ops,"POST","/search/rebuilds",body);assertThat(result.statusCode()).isEqualTo(200);first=json.readTree(result.body()).path("id").asText();
            rebuilder.run();assertThat(rebuildJobs.job(first).orElseThrow().status()).isEqualTo("SUCCEEDED");assertThat(sync.ackReady(id)).isFalse();
            assertThat(request(ops,"POST","/search/rebuilds",body).statusCode()).isEqualTo(200);rebuilder.run();
            assertThat(db().queryForObject("SELECT COUNT(*) FROM public_search_dead_recovery WHERE event_id=?",Integer.class,id)).isEqualTo(1);
            for(int attempt=1;attempt<=5;attempt++){
                db().update("UPDATE public_search_dead_recovery SET next_attempt_at=UTC_TIMESTAMP(6) WHERE event_id=? AND rebuild_id=?",id,first);sync.publish();
                assertThat(db().queryForObject("SELECT attempts FROM public_search_dead_recovery WHERE event_id=? AND rebuild_id=?",Integer.class,id,first)).isEqualTo(attempt);
            }
            sync.publish();assertThat(sync.ackReady(id)).isFalse();
        }finally{rabbitAdmin.declareBinding(binding);}
        var result=request(ops,"POST","/search/rebuilds",input(mapper.control().version(),UUID.randomUUID().toString(),"恢复路由后再次有限补发"));assertThat(result.statusCode()).isEqualTo(200);
        String second=json.readTree(result.body()).path("id").asText();rebuilder.run();assertThat(sync.ackReady(id)).isFalse();sync.publish();assertThat(sync.ackReady(id)).isTrue();
        assertThat(deliveries.status(id)).contains("DEAD_LETTER");
        assertThat(db().queryForObject("SELECT attempts=5 AND failed_at IS NOT NULL AND published_at IS NULL FROM public_search_dead_outbox WHERE event_id=?",Boolean.class,id)).isTrue();
        assertThat(db().queryForObject("SELECT attempts=5 AND failed_at IS NOT NULL AND published_at IS NULL FROM public_search_dead_recovery WHERE event_id=? AND rebuild_id=?",Boolean.class,id,first)).isTrue();
        assertThat(db().queryForObject("SELECT attempts=0 AND published_at IS NOT NULL FROM public_search_dead_recovery WHERE event_id=? AND rebuild_id=?",Boolean.class,id,second)).isTrue();
        var connection=rabbitConnection.createConnection();var channel=connection.createChannel(false);boolean found=false;
        try{com.rabbitmq.client.GetResponse message;while((message=channel.basicGet(SearchRabbitConfig.DEAD,false))!=null){
            var body=json.readTree(message.getBody());assertThat(body.propertyNames()).containsExactlyInAnyOrder("eventId","problemId","dataVersion","type");
            if(body.path("eventId").asText().equals(id)){found=true;assertThat(message.getProps().getDeliveryMode()).isEqualTo(2);assertThat(sync.ackReady(id)).isTrue();}channel.basicAck(message.getEnvelope().getDeliveryTag(),false);
        }assertThat(found).isTrue();}finally{channel.close();connection.close();}
    }
    @Test void expiredConsumerLeaseRejectsOldFinisherAndAReplacementClaimWins(){
        String id=db().queryForObject("SELECT id FROM public_search_outbox WHERE problem_id=1 ORDER BY data_version DESC LIMIT 1",String.class);sync.receive(id);
        String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();
        new TransactionTemplate(manager).executeWithoutResult(s->{deliveries.lock(id);assertThat(deliveries.claim(id,first)).isEqualTo(1);});
        db().update("UPDATE public_search_delivery SET lease_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE event_id=?",id);
        assertThat(deliveries.finish(id,first,"SUCCEEDED",null,1)).isZero();
        new TransactionTemplate(manager).executeWithoutResult(s->{deliveries.lock(id);assertThat(deliveries.claim(id,second)).isEqualTo(1);});
        assertThat(deliveries.finish(id,first,"SUCCEEDED",null,1)).isZero();assertThat(sync.ackReady(id)).isFalse();
        assertThat(deliveries.finish(id,second,"SUCCEEDED",null,1)).isEqualTo(1);assertThat(sync.ackReady(id)).isTrue();
        assertThat(db().queryForObject("SELECT attempts FROM public_search_delivery WHERE event_id=?",Integer.class,id)).isEqualTo(2);
    }
    @Test void readyIntentRecoversTheSameJobAfterLastAttemptAliasSwitchCrash()throws Exception {
        var ops=login("OPS_ADMIN");var created=request(ops,"POST","/search/rebuilds",input(mapper.control().version(),UUID.randomUUID().toString(),"验证第五次切换后的进程中断恢复"));assertThat(created.statusCode()).isEqualTo(200);
        String id=json.readTree(created.body()).path("id").asText();var job=rebuildJobs.job(id).orElseThrow();es.create(job.targetIndex(),id);
        for(var p:mapper.scan(0))es.index(job.targetIndex(),p,mapper.tags(p.problemId()));es.refresh(job.targetIndex());String uuid=es.uuid(job.targetIndex());
        db().update("UPDATE public_search_rebuild SET status='READY',attempts=5,target_uuid=?,target_epoch=?,lease_token=?,lease_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",uuid,epochs.publicRevision(),UUID.randomUUID().toString(),id);
        es.switchAlias(index,job.targetIndex());assertThat(service.search("火星",null,null,1,20).mode()).isEqualTo("TITLE_FALLBACK");
        rebuilder.run();assertThat(rebuildJobs.job(id).orElseThrow().status()).isEqualTo("SUCCEEDED");assertThat(rebuildJobs.job(id).orElseThrow().attempts()).isEqualTo(5);
        assertThat(mapper.control().indexUuid()).isEqualTo(uuid);assertThat(service.search("火星",null,null,1,20).mode()).isEqualTo("FULL_TEXT");
    }
    @Test void unknownCandidateDoesNotLeakItsBodyOrCountEvenWhenManifestCountIsUnchanged(){
        raw("DELETE","/"+index+"/_doc/1",null);
        raw("PUT","/"+index+"/_doc/999999?version_type=external&version=1",Map.of("problemId",999999,"dataVersion",1,"active",true,"title","索引 PRIVATE_CLASS_TITLE","statementText","火星 PRIVATE_CLASS_BODY","difficulty","EASY","tags",List.of()));es.refresh(index);
        var result=service.search("索引",null,null,1,20);assertThat(result.mode()).isEqualTo("TITLE_FALLBACK");assertThat(result.total()).isEqualTo(1);
        assertThat(json.writeValueAsString(result)).doesNotContain("PRIVATE_CLASS_TITLE","PRIVATE_CLASS_BODY");assertThat(result.items().getFirst().title()).isEqualTo("索引公开题");
    }
    @Test void governanceDuringRebuildRequiresAStableSecondSnapshotBeforeSwitch()throws Exception {
        var ops=login("OPS_ADMIN");var created=request(ops,"POST","/search/rebuilds",input(mapper.control().version(),UUID.randomUUID().toString(),"并发修改追赶验收"));assertThat(created.statusCode()).isEqualTo(200);
        String id=json.readTree(created.body()).path("id").asText();var job=rebuildJobs.job(id).orElseThrow();var once=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call->{if(once.compareAndSet(false,true)){
            var concurrent=new TransactionTemplate(manager);concurrent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            concurrent.executeWithoutResult(s->{new JdbcTemplate(apiSource).update("UPDATE problem SET statement_text='银河维护后的正文' WHERE id=1");invalidations.publicChanged();changes.changed(1);});
        }return call.callRealMethod();}).when(es).index(org.mockito.ArgumentMatchers.eq(job.targetIndex()),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList());
        rebuilder.run();assertThat(rebuildJobs.job(id).orElseThrow().status()).isEqualTo("RUNNING");assertThat(mapper.control().activeJob()).isEqualTo(id);
        assertThat(service.search("银河",null,null,1,20).mode()).isEqualTo("TITLE_FALLBACK");
        rebuilder.run();assertThat(rebuildJobs.job(id).orElseThrow().status()).isEqualTo("SUCCEEDED");assertThat(rebuildJobs.job(id).orElseThrow().attempts()).isEqualTo(2);
        assertThat(service.search("银河",null,null,1,20).mode()).isEqualTo("FULL_TEXT");assertThat(service.search("银河",null,null,1,20).total()).isEqualTo(1);
    }
    @Test void consumerCannotMarkAnOldGenerationWriteSuccessfulAfterRebuildSwitch(){
        change("UPDATE problem SET statement_text='银河新正文' WHERE id=1");String id=db().queryForObject("SELECT id FROM public_search_outbox WHERE problem_id=1 ORDER BY data_version DESC LIMIT 1",String.class);
        String newJob=UUID.randomUUID().toString(),next="forgeoj-public-"+newJob.replace("-","");es.create(next,newJob);
        for(var p:mapper.scan(0))es.index(next,p,mapper.tags(p.problemId()));es.refresh(next);String uuid=es.uuid(next);
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call->{Object result=call.callRealMethod();if(once.compareAndSet(false,true)){
            es.switchAlias(index,next);db().update("UPDATE public_search_control SET index_name=?,index_uuid=?,readable_epoch=? WHERE id=1",next,uuid,epochs.publicRevision());
        }return result;}).when(es).index(org.mockito.ArgumentMatchers.eq(index),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList());
        sync.receive(id);sync.process(id);assertThat(deliveries.status(id)).contains("QUEUED");assertThat(sync.ackReady(id)).isFalse();
        assertThat(db().queryForObject("SELECT error_code FROM public_search_delivery WHERE event_id=?",String.class,id)).isEqualTo("INDEX_GENERATION_CHANGED");
        db().update("UPDATE public_search_delivery SET next_attempt_at=UTC_TIMESTAMP(6) WHERE event_id=?",id);sync.process(id);assertThat(sync.ackReady(id)).isTrue();
    }
}
