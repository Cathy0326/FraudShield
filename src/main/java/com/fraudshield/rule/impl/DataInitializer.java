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

import java.util.List;

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

            // 新用户测试数据：2小时前创建的账号 → 命中HighAmountNewUserRule
            // Young-account demo: created 2h ago → trips HighAmountNewUserRule
            redisTemplate.opsForValue().set(
                    "user:created:USER-TEST-001",
                    String.valueOf(now - 2 * 60 * 60 * 1000L)
            );

            // 金额异常场景的用户池：每个都种为"老账号(10天) + 低历史均值($50)"。
            // 老账号让HighAmountNewUserRule不误触（否则未种子化的用户会被当成新账号），
            // 低均值让尖峰命中AbnormalAmountRule。模拟器在这些用户间轮换，EMA不会很快追平。
            // Amount-spike user pool: each seeded as an OLD account (10 days) with a low
            // historical average ($50). The old-account timestamp stops HighAmountNewUserRule
            // from mis-firing (an unseeded user is otherwise treated as new), while the low
            // average lets spikes trip AbnormalAmountRule. The simulator rotates across these
            // users so no single EMA catches up to the spikes too quickly.
            long tenDaysAgo = now - 10L * 24 * 60 * 60 * 1000L;
            for (String spikeUser : List.of(
                    "USER-TEST-002", "USER-SPIKE-1", "USER-SPIKE-2", "USER-SPIKE-3", "USER-SPIKE-4")) {
                redisTemplate.opsForValue().set("user:created:" + spikeUser, String.valueOf(tenDaysAgo));
                redisTemplate.opsForValue().set("user:avg_amount:" + spikeUser, "50.0");
            }

            // 黑名单测试数据：IP池轮换（见OrderSimulator）/ Blacklist seeds: IP pool (rotated; see OrderSimulator)
            redisTemplate.opsForSet().add("blacklist:ips",   "10.0.0.1", "10.0.0.2", "10.0.0.3");
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
