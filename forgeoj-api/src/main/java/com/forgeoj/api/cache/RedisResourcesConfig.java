/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.cache;
import io.lettuce.core.resource.*;
import org.springframework.context.annotation.*;
@Configuration
public class RedisResourcesConfig {
    @Bean(destroyMethod="shutdown")
    ClientResources lettuceClientResources(){return DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();}
    @Bean
    org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer boundedRedisClient(){
        return builder->builder.clientOptions(io.lettuce.core.ClientOptions.builder().requestQueueSize(256)
                .disconnectedBehavior(io.lettuce.core.ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build());
    }
}
