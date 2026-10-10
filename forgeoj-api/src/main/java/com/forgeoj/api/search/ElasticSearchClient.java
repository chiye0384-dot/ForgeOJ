/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.search;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

/** Fixed REST surface. No caller DSL/URL, redirects, raw error logging or unbounded bodies. */
@Component
public class ElasticSearchClient {
    public static final String ALIAS="forgeoj-public-search";
    private final boolean enabled;
    private final URI base;
    private final String authorization;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Semaphore requests=new Semaphore(4),probe=new Semaphore(1);
    private final AtomicLong failedUntil=new AtomicLong();
    public ElasticSearchClient(ObjectMapper json,@Value("${forgeoj.search.enabled:false}")boolean enabled,
            @Value("${forgeoj.search.url:https://localhost:9200}")String url,
            @Value("${forgeoj.search.username:}")String user,@Value("${forgeoj.search.password:}")String password,
            @Value("${forgeoj.search.allow-loopback-http:false}")boolean loopbackHttp){
        this.json=json;this.enabled=enabled;base=URI.create(url);
        boolean local=Set.of("localhost","127.0.0.1","[::1]").contains(base.getHost()==null?"":base.getHost());
        if(enabled&&(base.getHost()==null||base.getRawUserInfo()!=null||base.getRawQuery()!=null||base.getFragment()!=null
            ||!Set.of("","/").contains(base.getPath())||!("https".equals(base.getScheme())||loopbackHttp&&local&&"http".equals(base.getScheme()))
            ||user.isBlank()||password.isBlank()||user.contains(":")))throw new IllegalArgumentException("Invalid search connection settings");
        authorization="Basic "+Base64.getEncoder().encodeToString((user+":"+password).getBytes(StandardCharsets.UTF_8));
        http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(250)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public boolean enabled(){return enabled;}
    public long count(String index){name(index);var r=call("GET","/"+index+"/_count",null,Set.of(200));
        if(r.path("_shards").path("failed").asInt(-1)!=0||!r.path("count").isIntegralNumber()||r.path("count").asLong(-1)<0)throw unavailable();return r.path("count").asLong();}
    public record Candidate(long problemId,long dataVersion,JsonNode document) {}
    public List<Candidate> search(String keyword,String difficulty,String tag){
        return searchTags(keyword,difficulty,tag==null?null:List.of(tag));
    }
    public List<Candidate> searchTags(String keyword,String difficulty,List<String> tags){
        var filters=new ArrayList<Object>();filters.add(Map.of("term",Map.of("active",true)));
        if(difficulty!=null)filters.add(Map.of("term",Map.of("difficulty",difficulty)));
        if(tags!=null){if(tags.size()>100)throw unavailable();filters.add(Map.of("terms",Map.of("tags",tags)));}
        var query=Map.of("size",1000,"track_total_hits",true,"version",true,
            "sort",List.of(Map.of("_score","desc"),Map.of("problemId","asc")),
            "query",Map.of("bool",Map.of("filter",filters,"must",List.of(Map.of("multi_match",Map.of("query",keyword,"fields",List.of("title^2","statementText")))))));
        JsonNode root=call("POST","/"+ALIAS+"/_search",query,Set.of(200));
        var hits=root.path("hits");var total=hits.path("total");var values=hits.path("hits");
        if(root.path("timed_out").asBoolean(true)||root.path("_shards").path("failed").asInt(-1)!=0
            ||!"eq".equals(total.path("relation").asText())||!values.isArray()
            ||total.path("value").asLong(-1)!=values.size()||values.size()>1000)throw unavailable();
        var result=new ArrayList<Candidate>();var ids=new HashSet<Long>();
        for(var hit:values){
            var doc=hit.path("_source");long id=integer(doc.path("problemId")),version=integer(doc.path("dataVersion"));
            if(!Long.toString(id).equals(hit.path("_id").asText())||integer(hit.path("_version"))!=version||!ids.add(id))throw unavailable();
            result.add(new Candidate(id,version,doc));
        }
        return List.copyOf(result);
    }
    public JsonNode document(SearchMapper.Projection p,List<String> tags){
        Map<String,Object> doc=new LinkedHashMap<>();doc.put("problemId",p.problemId());doc.put("dataVersion",p.dataVersion());doc.put("active",p.active());
        if(p.active()){doc.put("title",p.title());doc.put("statementText",p.statementText());doc.put("difficulty",p.difficulty());doc.put("tags",tags);}
        // Round-trip normalizes integral node widths to the same parser used for ES responses.
        return json.readTree(json.writeValueAsBytes(doc));
    }
    public void index(String index,SearchMapper.Projection p,List<String> tags){
        name(index);var result=call("PUT","/"+index+"/_doc/"+p.problemId()+"?version_type=external&version="+p.dataVersion(),document(p,tags),Set.of(200,201,409));
        // A conflict is idempotent only after verifying the current persisted version.
        if(result.path("error").isObject()){
            var current=call("GET","/"+index+"/_doc/"+p.problemId(),null,Set.of(200));
            long version=integer(current.path("_version"));
            if(version<p.dataVersion()||version==p.dataVersion()&&!current.path("_source").equals(document(p,tags)))throw unavailable();
        }
    }
    public void create(String index,String job){
        name(index);UUID.fromString(job);
        Map<String,Object> props=new LinkedHashMap<>();
        props.put("problemId",Map.of("type","long"));props.put("dataVersion",Map.of("type","long"));props.put("active",Map.of("type","boolean"));
        props.put("title",Map.of("type","text","analyzer","cjk"));props.put("statementText",Map.of("type","text","analyzer","cjk"));
        props.put("difficulty",Map.of("type","keyword"));props.put("tags",Map.of("type","keyword"));
        call("PUT","/"+index,Map.of("settings",Map.of("number_of_shards",1,"number_of_replicas",0),
            "mappings",Map.of("dynamic","strict","_meta",Map.of("forgeojRebuildId",job),"properties",props)),Set.of(200));
    }
    public String uuid(String index){
        name(index);var root=call("GET","/"+index+"/_settings/index.uuid",null,Set.of(200));
        if(root.size()!=1||!root.has(index))throw unavailable();
        String uuid=root.path(index).path("settings").path("index").path("uuid").asText();
        if(!uuid.matches("[A-Za-z0-9_-]{1,64}"))throw unavailable();return uuid;
    }
    public record Alias(String index,String uuid) {}
    public Alias alias(){
        return optionalAlias().orElseThrow(ElasticSearchClient::unavailable);
    }
    public Optional<Alias> optionalAlias(){
        var root=call("GET","/_alias/"+ALIAS,null,Set.of(200,404));
        if(root.has("error"))return Optional.empty();
        if(root.size()!=1)throw unavailable();String index=root.propertyNames().iterator().next();name(index);
        if(!root.path(index).path("aliases").has(ALIAS))throw unavailable();return Optional.of(new Alias(index,uuid(index)));
    }
    public void verifyOwned(String index,String job){
        name(index);var mappings=call("GET","/"+index+"/_mapping",null,Set.of(200)).path(index).path("mappings");
        if(!"strict".equals(mappings.path("dynamic").asText())||!job.equals(mappings.path("_meta").path("forgeojRebuildId").asText())
            ||!mappings.path("properties").propertyNames().equals(Set.of("problemId","dataVersion","active","title","statementText","difficulty","tags"))
            ||!"cjk".equals(mappings.path("properties").path("title").path("analyzer").asText())
            ||!"cjk".equals(mappings.path("properties").path("statementText").path("analyzer").asText()))throw unavailable();
    }
    public boolean exists(String index){name(index);var result=call("GET","/"+index+"/_settings/index.uuid",null,Set.of(200,404));return result.has(index);}
    public void refresh(String index){name(index);var r=call("POST","/"+index+"/_refresh",null,Set.of(200));if(r.path("_shards").path("failed").asInt(-1)!=0)throw unavailable();}
    public void switchAlias(String previous,String target){
        name(target);var actions=new ArrayList<Object>();
        if(previous!=null){name(previous);actions.add(Map.of("remove",Map.of("index",previous,"alias",ALIAS,"must_exist",false)));}
        actions.add(Map.of("add",Map.of("index",target,"alias",ALIAS)));
        var r=call("POST","/_aliases",Map.of("actions",actions),Set.of(200));if(!r.path("acknowledged").asBoolean(false))throw unavailable();
    }
    private static void name(String name){if(!ALIAS.equals(name)&&(name==null||!name.matches("forgeoj-public-[a-f0-9]{32}")))throw new IllegalArgumentException("Invalid managed search index");}
    private static long integer(JsonNode node){if(!node.isIntegralNumber()||!node.canConvertToLong())throw unavailable();long n=node.asLong();if(n<1||n>9007199254740991L)throw unavailable();return n;}
    private JsonNode call(String method,String path,Object body,Set<Integer> accepted){
        long failed=failedUntil.get();if(!enabled||failed!=0&&System.nanoTime()-failed<0||!requests.tryAcquire())throw unavailable();
        boolean probing=failed!=0,held=false;CompletableFuture<HttpResponse<byte[]>> pending=null;
        try{
            if(probing){held=probe.tryAcquire();if(!held)throw unavailable();}
            byte[] bytes=body==null?new byte[0]:json.writeValueAsBytes(body);if(bytes.length>1048576)throw unavailable();
            var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(1)).header("Authorization",authorization).header("Content-Type","application/json");
            pending=http.sendAsync(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),info->new LimitedBody());
            var response=pending.get(1,TimeUnit.SECONDS);
            if(!accepted.contains(response.statusCode()))throw unavailable();
            JsonNode result=json.readTree(response.body());if(result==null||!result.isObject())throw unavailable();
            failedUntil.set(0);return result;
        }catch(Exception failure){if(pending!=null)pending.cancel(true);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();failedUntil.set(System.nanoTime()+TimeUnit.SECONDS.toNanos(2));throw unavailable();}
        finally{if(held)probe.release();requests.release();}
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(Long.MAX_VALUE);}
        public void onNext(List<ByteBuffer> buffers){for(var b:buffers){if(bytes.size()+b.remaining()>262144){subscription.cancel();result.completeExceptionally(unavailable());return;}byte[] part=new byte[b.remaining()];b.get(part);bytes.writeBytes(part);}}
        public void onError(Throwable failure){result.completeExceptionally(failure);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    public static SearchUnavailable unavailable(){return new SearchUnavailable();}
    public static final class SearchUnavailable extends RuntimeException {private SearchUnavailable(){super("SEARCH_UNAVAILABLE",null,false,false);}}
}
