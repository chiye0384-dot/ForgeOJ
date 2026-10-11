/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.observability;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.availability.ApplicationAvailability;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MonitoringRuntimeTests {
    @Test void monitoringIsAbsentByDefaultAndDoesNotRequestBusinessDependencies(){
        try(var context=new AnnotationConfigApplicationContext()){context.register(MonitoringRuntime.class);context.refresh();assertTrue(context.getBeansOfType(MonitoringRuntime.class).isEmpty());}
    }
    @Test void enablingWithoutCredentialFailsBeforeOpeningThePort(){
        try(var context=new AnnotationConfigApplicationContext()){
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",java.util.Map.of("forgeoj.monitoring.enabled","true","forgeoj.monitoring.port","0")));
            context.registerBean(MonitoringSnapshot.class,()->mock(MonitoringSnapshot.class));context.registerBean(ApplicationAvailability.class,()->mock(ApplicationAvailability.class));context.register(MonitoringRuntime.class);
            var error=assertThrows(org.springframework.beans.BeansException.class,context::refresh);assertTrue(error.getMostSpecificCause().getMessage().contains("Invalid monitoring credential"));
        }
    }
    @Test void privateNetworkBindingRequiresAnAdditionalExplicitOptIn(){
        assertThrows(IllegalArgumentException.class,()->new MonitoringRuntime(mock(MonitoringSnapshot.class),mock(ApplicationAvailability.class),"0.0.0.0",0,"public-test-only-monitoring-token-32bytes",false));
    }
}
