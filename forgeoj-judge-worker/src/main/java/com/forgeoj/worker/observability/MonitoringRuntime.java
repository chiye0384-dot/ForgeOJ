/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.observability;
import java.io.IOException;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.stereotype.Component;
@Component @ConditionalOnProperty(name="forgeoj.monitoring.enabled",havingValue="true")
public final class MonitoringRuntime implements SmartLifecycle,DisposableBean {
    private final MonitoringHttpServer server;private final JdbcTemplate db;private final RabbitListenerEndpointRegistry listeners;
    private final ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"forgeoj-monitoring-sampler");t.setDaemon(true);return t;});
    private record Sample(long atNanos,long atSeconds,boolean database,boolean consumers) {}
    private volatile Sample sample=new Sample(0,0,false,false);private volatile boolean running;
    public MonitoringRuntime(DataSource source,RabbitListenerEndpointRegistry listeners,ApplicationAvailability availability,@Value("${forgeoj.monitoring.bind:127.0.0.1}")String bind,@Value("${forgeoj.monitoring.port:9092}")int port,@Value("${forgeoj.monitoring.token:}")String token,@Value("${forgeoj.monitoring.allow-private-network-bind:false}")boolean allowPrivate)throws IOException{
        if(!bind.equals("127.0.0.1")&&!allowPrivate)throw new IllegalArgumentException("Private monitoring bind requires explicit opt-in");
        db=new JdbcTemplate(source);db.setQueryTimeout(2);this.listeners=listeners;
        server=new MonitoringHttpServer(bind,port,token,()->MonitoringMetrics.PROCESS.render()+renderState(),()->ready(availability));
    }
    void refresh(){
        boolean connected=false;try{connected=Long.valueOf(0).equals(db.queryForObject("SELECT COUNT(*) FROM judge_task WHERE 1=0",Long.class));}catch(RuntimeException unavailable){}
        var containers=listeners.getListenerContainers();boolean active=!containers.isEmpty()&&containers.stream().allMatch(c->c.isRunning()&&c instanceof SimpleMessageListenerContainer simple&&simple.getActiveConsumerCount()>0);
        sample=new Sample(System.nanoTime(),System.currentTimeMillis()/1000,connected,active);
    }
    private static boolean fresh(Sample s){return s.database()&&System.nanoTime()-s.atNanos()<45_000_000_000L;}
    private boolean ready(ApplicationAvailability availability){var s=sample;return availability.getReadinessState()==ReadinessState.ACCEPTING_TRAFFIC&&fresh(s)&&s.consumers();}
    private String renderState(){var s=sample;return "# HELP forgeoj_monitoring_collection_success Whether the most recent database schema probe succeeded.\n# TYPE forgeoj_monitoring_collection_success gauge\nforgeoj_monitoring_collection_success "+(s.database()?1:0)+"\n# HELP forgeoj_monitoring_snapshot_fresh Whether a successful database probe is less than 45 seconds old.\n# TYPE forgeoj_monitoring_snapshot_fresh gauge\nforgeoj_monitoring_snapshot_fresh "+(fresh(s)?1:0)+"\n# HELP forgeoj_worker_consumers_ready Whether all configured consumers report active connections.\n# TYPE forgeoj_worker_consumers_ready gauge\nforgeoj_worker_consumers_ready "+(s.consumers()?1:0)+"\n# HELP forgeoj_monitoring_last_observation_seconds Unix time of the last dependency observation.\n# TYPE forgeoj_monitoring_last_observation_seconds gauge\nforgeoj_monitoring_last_observation_seconds "+s.atSeconds()+"\n";}
    @Override public synchronized void start(){if(!running){server.start();sampler.scheduleWithFixedDelay(this::refresh,0,15,TimeUnit.SECONDS);running=true;}}
    @Override public synchronized void stop(){server.close();sampler.shutdownNow();running=false;}
    @Override public boolean isRunning(){return running;}
    @Override public void destroy(){stop();}
    int port(){return server.port();}
}
