package com.fraudshield.rule.impl;

import com.fraudshield.model.AppUser;
import com.fraudshield.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 开发环境数据初始化 — 应用启动后向Redis和H2写入测试数据
 * Dev-only data seeder — seeds Redis test data and default H2 users on startup.
 * Excluded from the "test" profile so unit tests don't need live infrastructure.
 */
@Component
@Profile("!test")
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final StringRedisTemplate redisTemplate;
    private final AppUserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public DataInitializer(StringRedisTemplate redisTemplate,
                           AppUserRepository userRepository,
                           BCryptPasswordEncoder passwordEncoder) {
        this.redisTemplate   = redisTemplate;
        this.userRepository  = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        seedRedis();
        seedUsers();
    }

    // Redis is unavailable in some environments (e.g. a fresh Container Apps deploy
    // before Azure Cache for Redis is wired up); seeding is dev convenience only,
    // so a connection failure here must not take down the whole app context.
    private void seedRedis() {
        try {
            long now = System.currentTimeMillis();

            // 新用户测试数据：2小时前创建的账号 (in the 24h new-user window)
            redisTemplate.opsForValue().set(
                    "user:created:USER-TEST-001",
                    String.valueOf(now - 2 * 60 * 60 * 1000L)
            );

            // 历史均值测试数据 / Historical EMA for AbnormalAmountRule
            redisTemplate.opsForValue().set("user:avg_amount:USER-TEST-002", "50.0");

            // 黑名单测试数据 / Blacklist seeds
            redisTemplate.opsForSet().add("blacklist:ips",   "10.0.0.1");
            redisTemplate.opsForSet().add("blacklist:users", "USER-BAD-001");

            log.info("DataInitializer: seeded Redis test data");
        } catch (Exception e) {
            log.warn("DataInitializer: Redis unavailable, skipping test data seed: {}", e.getMessage());
        }
    }

    private void seedUsers() {
        createUserIfAbsent("admin",    "Admin@123",  "ROLE_ADMIN");
        createUserIfAbsent("operator", "Op@123",     "ROLE_OPERATOR");
        log.info("DataInitializer: created default users admin, operator");
    }

    private void createUserIfAbsent(String username, String rawPassword, String role) {
        if (userRepository.findByUsername(username).isEmpty()) {
            userRepository.save(AppUser.builder()
                    .username(username)
                    .password(passwordEncoder.encode(rawPassword))
                    .role(role)
                    .build());
        }
    }
}
