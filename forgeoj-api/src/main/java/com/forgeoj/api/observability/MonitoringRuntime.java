/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.observability;
import java.io.IOException;
import java.util.concurrent.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
@Component @ConditionalOnProperty(name="forgeoj.monitoring.enabled",havingValue="true")
public final class MonitoringRuntime implements SmartLifecycle,DisposableBean {
    private final MonitoringHttpServer server;private final MonitoringSnapshot snapshot;
    private final ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"forgeoj-monitoring-sampler");t.setDaemon(true);return t;});
    private volatile boolean running;
    public MonitoringRuntime(MonitoringSnapshot snapshot,ApplicationAvailability availability,@Value("${forgeoj.monitoring.bind:127.0.0.1}")String bind,@Value("${forgeoj.monitoring.port:9091}")int port,@Value("${forgeoj.monitoring.token:}")String token,@Value("${forgeoj.monitoring.allow-private-network-bind:false}")boolean allowPrivate)throws IOException{
        if(!bind.equals("127.0.0.1")&&!allowPrivate)throw new IllegalArgumentException("Private monitoring bind requires explicit opt-in");this.snapshot=snapshot;
        server=new MonitoringHttpServer(bind,port,token,()->MonitoringMetrics.PROCESS.render()+snapshot.render(),()->availability.getReadinessState()==ReadinessState.ACCEPTING_TRAFFIC&&snapshot.fresh());
    }
    @Override public synchronized void start(){if(!running){server.start();sampler.scheduleWithFixedDelay(snapshot::refresh,0,15,TimeUnit.SECONDS);running=true;}}
    @Override public synchronized void stop(){server.close();sampler.shutdownNow();running=false;}
    @Override public boolean isRunning(){return running;}
    @Override public void destroy(){stop();}
    int port(){return server.port();}
}
