package com.velora.backend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Categories are DB-seeded/migration-only (no create/update endpoint), so a
 * long TTL with no eviction path is safe - restarting the app is the only
 * way the list ever changes.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("categories");
        manager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).maximumSize(1));
        manager.registerCustomCache("portfolioSearch",
                Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(200).build());
        return manager;
    }
}
