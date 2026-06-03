package com.sism.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
@EnableCaching
public class ApplicationCacheConfig {

    @Bean("applicationCacheManager")
    @Primary
    public CacheManager applicationCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                "userByUsername",
                "userByEmail",
                "userByPhone",
                "roleCodesByUserId",
                "orgById",
                "currentUserSummary",
                "plansPage",
                "plansByCycle",
                "planById"
        );
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(1_000));
        return cacheManager;
    }
}
