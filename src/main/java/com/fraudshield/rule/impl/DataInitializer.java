package com.fraudshield.rule.impl;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 开发环境数据初始化 — 应用启动后向Redis写入测试数据
 * Dev-only data seeder — writes known test data to Redis on startup.
 *
 * Uses CommandLineRunner so it runs after the full ApplicationContext is ready.
 * Excluded from the "test" profile so unit tests don't need a live Redis.
 */
@Component
@Profile("!test")
public class DataInitializer implements CommandLineRunner {

    private final StringRedisTemplate redisTemplate;

    public DataInitializer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void run(String... args) {
        long now = System.currentTimeMillis();

        // 新用户测试数据：2小时前创建的账号
        // New-user test data: account created 2 hours ago (within the 24h window)
        redisTemplate.opsForValue().set(
                "user:created:USER-TEST-001",
                String.valueOf(now - 2 * 60 * 60 * 1000L)
        );

        // 历史均值测试数据：用户平均订单金额 50.0
        // Historical average test data: user's EMA is 50.0
        redisTemplate.opsForValue().set("user:avg_amount:USER-TEST-002", "50.0");

        // 黑名单测试数据
        // Blacklist test data
        redisTemplate.opsForSet().add("blacklist:ips",   "10.0.0.1");
        redisTemplate.opsForSet().add("blacklist:users", "USER-BAD-001");

        System.out.println("[DataInitializer] Redis test data seeded.");
    }
}
