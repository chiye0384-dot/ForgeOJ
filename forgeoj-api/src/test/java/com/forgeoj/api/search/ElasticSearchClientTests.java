/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.search;
import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

class ElasticSearchClientTests {
    HttpServer server;ExecutorService executor;String url;
    final ObjectMapper json=new ObjectMapper();final AtomicReference<String> body=new AtomicReference<>();
    final AtomicReference<String> requestBody=new AtomicReference<>();final AtomicInteger status=new AtomicInteger(200),count=new AtomicInteger();final AtomicLong delay=new AtomicLong();
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newFixedThreadPool(8);server.setExecutor(executor);
        server.createContext("/",exchange->{count.incrementAndGet();requestBody.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            try{Thread.sleep(delay.get());byte[] bytes=body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(java.io.IOException ignored){}finally{exchange.close();}});
        server.start();url="http://127.0.0.1:"+server.getAddress().getPort();body.set(result(List.of(hit(1,2)),1,"eq",false,0));
    }
    @AfterEach void stop(){server.stop(0);executor.shutdownNow();}
    ElasticSearchClient client(){return new ElasticSearchClient(json,true,url,"fixture","fixture",true);}
    Map<String,Object> hit(long id,long version){return Map.of("_id",Long.toString(id),"_version",version,"_source",Map.of("problemId",id,"dataVersion",version,"active",true,"title","public title","statementText","中文正文","difficulty","EASY","tags",List.of("数学")));}
    String result(List<?> hits,long total,String relation,boolean timedOut,int failed){return json.writeValueAsString(Map.of("timed_out",timedOut,"_shards",Map.of("failed",failed),"hits",Map.of("total",Map.of("value",total,"relation",relation),"hits",hits)));}
    @Test void callerKeywordRemainsADataValueWithBoundedExplicitQuery(){
        String keyword="中文\" } injected: {";assertThat(client().search(keyword,"EASY","数学")).hasSize(1);
        var query=json.readTree(requestBody.get());assertThat(query.path("size").asInt()).isEqualTo(1000);assertThat(query.path("track_total_hits").asBoolean()).isTrue();
        assertThat(query.path("query").path("bool").path("must").get(0).path("multi_match").path("query").asText()).isEqualTo(keyword);
        assertThat(query.path("query").path("bool").path("filter").size()).isEqualTo(3);
    }
    @Test void inexactMismatchedDuplicateOversizedAndFailedCandidateSetsAreRejected(){
        for(String malformed:List.of(result(List.of(hit(1,2)),1,"gte",false,0),result(List.of(hit(1,2)),2,"eq",false,0),
            result(List.of(hit(1,2),hit(1,2)),2,"eq",false,0),result(List.of(hit(1,2)),1,"eq",true,0),result(List.of(hit(1,2)),1,"eq",false,1),
            result(Collections.nCopies(1001,hit(1,2)),1001,"eq",false,0))){body.set(malformed);assertThatThrownBy(()->client().search("中文",null,null)).isInstanceOf(ElasticSearchClient.SearchUnavailable.class);}
    }
    @Test void illegalIdsVersionMetadataAndJsonNeverProduceCandidates(){
        var wrong=new HashMap<String,Object>(hit(1,2));wrong.put("_version",1);
        for(String malformed:List.of(result(List.of(hit(0,1)),1,"eq",false,0),result(List.of(hit(9007199254740992L,1)),1,"eq",false,0),result(List.of(wrong),1,"eq",false,0),"[]","not-json")){
            body.set(malformed);assertThatThrownBy(()->client().search("中文",null,null)).isInstanceOf(ElasticSearchClient.SearchUnavailable.class);
        }
    }
    @Test void oversizedResponseHttpFailuresAndRedirectAreBounded(){
        body.set("x".repeat(262145));assertThatThrownBy(()->client().search("中文",null,null)).isInstanceOf(ElasticSearchClient.SearchUnavailable.class);
        body.set("{}");for(int code:List.of(301,401,500)){status.set(code);assertThatThrownBy(()->client().search("中文",null,null)).isInstanceOf(ElasticSearchClient.SearchUnavailable.class);}
    }
    @Test void timeoutOpensCircuitAndRecoveryProbeResumesAfterCooldown()throws Exception {
        var client=client();delay.set(2000);long started=System.nanoTime();assertThatThrownBy(()->client.search("中文",null,null)).isInstanceOf(ElasticSearchClient.SearchUnavailable.class);
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(2));int requests=count.get();
        assertThatThrownBy(()->client.search("中文",null,null)).isInstanceOf(ElasticSearchClient.SearchUnavailable.class);assertThat(count.get()).isEqualTo(requests);
        delay.set(0);Thread.sleep(2100);assertThat(client.search("中文",null,null)).hasSize(1);
    }
    @Test void fourInflightRequestsDoNotQueueAnExtraExternalRequest()throws Exception {
        var client=client();delay.set(2000);
        try(var pool=Executors.newFixedThreadPool(4)){
            var calls=new ArrayList<Future<?>>();for(int i=0;i<4;i++)calls.add(pool.submit(()->assertThatThrownBy(()->client.search("中文",null,null)).isInstanceOf(ElasticSearchClient.SearchUnavailable.class)));
            org.awaitility.Awaitility.await().atMost(Duration.ofMillis(800)).until(()->count.get()==4);
            long start=System.nanoTime();assertThatThrownBy(()->client.search("中文",null,null)).isInstanceOf(ElasticSearchClient.SearchUnavailable.class);
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofMillis(250));assertThat(count.get()).isEqualTo(4);
            for(var call:calls)call.get(4,TimeUnit.SECONDS);
        }
    }
    @Test void productionConfigurationRequiresTlsAndNoEmbeddedCredentials(){
        assertThatThrownBy(()->new ElasticSearchClient(json,true,url,"fixture","fixture",false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ElasticSearchClient(json,true,"http://external.example:9200","fixture","fixture",true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ElasticSearchClient(json,true,"https://user:pass@localhost:9200","fixture","fixture",false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ElasticSearchClient(json,true,"https://localhost:9200/path","fixture","fixture",false)).isInstanceOf(IllegalArgumentException.class);
        var off=new ElasticSearchClient(json,false,url,"","",false);assertThatThrownBy(()->off.search("中文",null,null)).isInstanceOf(ElasticSearchClient.SearchUnavailable.class);assertThat(count.get()).isZero();
    }
    @Test void plaintextSnippetsRespectUnicodeCodePointBounds(){
        String text="😀".repeat(80)+"火星"+"😀".repeat(180);var snippets=PublicSearchService.highlights("标题",text,"火星");
        assertThat(snippets).hasSize(2);String joined=snippets.get(1).stream().map(PublicSearchService.Segment::text).reduce("",String::concat);
        assertThat(joined.codePointCount(0,joined.length())).isLessThanOrEqualTo(160);assertThat(snippets.get(1)).contains(new PublicSearchService.Segment("火星",true));
        assertThat(joined.codePoints().anyMatch(c->c>=0xd800&&c<=0xdfff)).isFalse();
    }
}
