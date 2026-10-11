/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.observability;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MonitoringHttpServerTests {
    private static final String TOKEN="public-test-only-monitoring-token-32bytes";
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private HttpResponse<String> request(MonitoringHttpServer server,String method,String path,String token)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+path)).timeout(Duration.ofSeconds(3));
        if(token!=null)b.header("Authorization","Bearer "+token);
        return client.send(b.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void dedicatedCredentialExactPathsAndMethodsProtectEveryEndpoint()throws Exception{
        var calls=new AtomicInteger();var ready=new AtomicBoolean();
        try(var server=new MonitoringHttpServer("127.0.0.1",0,TOKEN,()->{calls.incrementAndGet();return "# TYPE test_total counter\ntest_total 1\n";},ready::get)){
            server.start();for(String path:new String[]{"/metrics","/health/live","/health/ready"}){
                var denied=request(server,"GET",path,null);assertEquals(401,denied.statusCode());assertEquals("",denied.body());assertEquals("no-store",denied.headers().firstValue("Cache-Control").orElseThrow());
                assertEquals(401,request(server,"GET",path,"ordinary-or-admin-jwt").statusCode());
            }
            assertEquals(0,calls.get());assertEquals(404,request(server,"GET","/metrics/private",TOKEN).statusCode());assertEquals(404,request(server,"GET","/%6det rics".replace(" ",""),TOKEN).statusCode());
            assertEquals(400,request(server,"GET","/metrics?user=secret",TOKEN).statusCode());assertEquals(405,request(server,"POST","/metrics",TOKEN).statusCode());
            assertEquals(200,request(server,"GET","/health/live",TOKEN).statusCode());assertEquals(503,request(server,"GET","/health/ready",TOKEN).statusCode());ready.set(true);assertEquals(200,request(server,"GET","/health/ready",TOKEN).statusCode());
            var response=request(server,"GET","/metrics",TOKEN);assertEquals(200,response.statusCode());assertEquals(1,calls.get());assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("version=0.0.4"));assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        }
    }
    @Test void supplierFailureAndOversizedResponseFailClosedWithoutExceptionText()throws Exception{
        for(var supplier:java.util.List.<java.util.function.Supplier<String>>of(()->{throw new IllegalStateException("private-token-secret");},()->"x".repeat(262145))){
            try(var server=new MonitoringHttpServer("127.0.0.1",0,TOKEN,supplier,()->true)){server.start();var r=request(server,"GET","/metrics",TOKEN);assertEquals(503,r.statusCode());assertEquals("",r.body());}
        }
    }
    @Test void invalidCredentialIsRejectedBeforeBindingAndCloseStopsOnlyThisServer()throws Exception{
        var error=assertThrows(IllegalArgumentException.class,()->new MonitoringHttpServer("127.0.0.1",0,"private-too-short",()->"",()->true));assertFalse(error.getMessage().contains("private-too-short"));
        assertThrows(IllegalArgumentException.class,()->new MonitoringHttpServer("external.example",0,TOKEN,()->"",()->true));
        var first=new MonitoringHttpServer("127.0.0.1",0,TOKEN,()->"",()->true);try(var second=new MonitoringHttpServer("127.0.0.1",0,TOKEN,()->"",()->true)){
            first.start();second.start();first.close();first.close();assertThrows(java.io.IOException.class,()->request(first,"GET","/metrics",TOKEN));assertEquals(200,request(second,"GET","/health/live",TOKEN).statusCode());assertThrows(IllegalStateException.class,first::start);
        }finally{first.close();}
    }
}
