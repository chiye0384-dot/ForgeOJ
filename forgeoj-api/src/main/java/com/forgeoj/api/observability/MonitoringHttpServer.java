/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Private, opt-in transport. The caller owns this server and must close it. */
public final class MonitoringHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final byte[] authorizationDigest;
    private final Supplier<String> metrics;
    private final BooleanSupplier ready;
    private boolean started,closed;
    public MonitoringHttpServer(String bind,int port,String token,Supplier<String> metrics,BooleanSupplier ready)throws IOException{
        if(!java.util.Set.of("127.0.0.1","0.0.0.0").contains(bind)||port<0||port>65535)throw new IllegalArgumentException("Invalid private monitoring bind");
        if(token==null||!token.matches("[A-Za-z0-9_-]{32,128}"))throw new IllegalArgumentException("Invalid monitoring credential");
        this.metrics=java.util.Objects.requireNonNull(metrics);this.ready=java.util.Objects.requireNonNull(ready);
        authorizationDigest=digest("Bearer "+token);
        server=HttpServer.create(new InetSocketAddress(bind,port),8);
        executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var thread=new Thread(r,"forgeoj-monitoring");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);server.createContext("/",this::handle);
    }
    public synchronized void start(){if(closed)throw new IllegalStateException("Monitoring server closed");if(!started){server.start();started=true;}}
    public int port(){return server.getAddress().getPort();}
    private void handle(HttpExchange exchange)throws IOException{
        try(exchange){
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            var headers=exchange.getRequestHeaders().get("Authorization");
            if(headers==null||headers.size()!=1||headers.get(0).length()>135||!MessageDigest.isEqual(authorizationDigest,digest(headers.get(0)))){empty(exchange,401);return;}
            String path=exchange.getRequestURI().getRawPath();
            if(!java.util.Set.of("/metrics","/health/live","/health/ready").contains(path)){empty(exchange,404);return;}
            if(!exchange.getRequestMethod().equals("GET")){exchange.getResponseHeaders().set("Allow","GET");empty(exchange,405);return;}
            if(exchange.getRequestURI().getRawQuery()!=null){empty(exchange,400);return;}
            try{
                if(path.equals("/metrics")){
                    byte[] body=metrics.get().getBytes(StandardCharsets.UTF_8);
                    if(body.length>262144){empty(exchange,503);return;}
                    exchange.getResponseHeaders().set("Content-Type","text/plain; version=0.0.4; charset=utf-8");
                    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                }else empty(exchange,path.equals("/health/live")||ready.getAsBoolean()?200:503);
            }catch(RuntimeException failure){empty(exchange,503);}
        }
    }
    private static void empty(HttpExchange exchange,int status)throws IOException{exchange.sendResponseHeaders(status,-1);}
    private static byte[] digest(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}}
    @Override public synchronized void close(){if(!closed){closed=true;server.stop(0);executor.shutdownNow();}}
}
