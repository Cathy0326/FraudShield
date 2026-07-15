package com.fraudshield.service;

import com.fraudshield.rule.RiskRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 规则运行时配置 — 让风控负责人在不重新部署的情况下调整规则。
 * Runtime rule configuration: lets a fraud lead tune rules without a redeploy —
 * the "act" half of the diagnose→act loop the Rule Health Board opens.
 *
 * <p>目前支持两项集中式、无需改动各规则实现的开关：
 *   <li><b>enabled</b>：关掉一条太吵的规则（引擎直接跳过它）。
 *   <li><b>weightOverride</b>：手动压低/抬高某规则在noisy-OR里的话语权，覆盖由精度
 *       自动推导的权重（{@link RuleWeightService}）。
 * Two centralized toggles that need no change to any rule implementation:
 * enabled (the engine skips a disabled rule) and weightOverride (manually set a
 * rule's say in the noisy-OR combination, overriding the precision-derived weight).
 *
 * <p>存Redis：跨重启保留（compose里Redis开了appendonly），且与规则命中计数器同层。
 * 读侧对Redis故障"fail-open"：连不上就当作启用、无覆盖 —— 宁可多检测，绝不因基础设施
 * 抖动而**静默停用**一条欺诈规则。
 * Stored in Redis (survives restarts via appendonly, same layer as the hot counters).
 * Reads fail OPEN on a Redis outage — treat as enabled / no override — because we must
 * never silently stop detecting fraud due to an infra blip.
 */
@Service
public class RuleConfigService {

    private static final Logger log = LoggerFactory.getLogger(RuleConfigService.class);
    private static final String KEY_PREFIX = "rule:config:";   // hash per rule: {enabled, weight}
    private static final String F_ENABLED = "enabled";
    private static final String F_WEIGHT = "weight";

    private final StringRedisTemplate redis;
    private final List<String> ruleNames;

    public RuleConfigService(StringRedisTemplate redis, List<RiskRule> rules) {
        this.redis = redis;
        // 引擎里注册的所有规则名 —— 配置面板据此列出全部规则（含尚无命中的）
        // Every rule registered in the engine, so the console lists them all (even
        // ones with no hits yet).
        this.ruleNames = rules.stream().map(RiskRule::getRuleName).distinct().sorted().toList();
    }

    public List<String> ruleNames() {
        return ruleNames;
    }

    /** 规则是否启用（默认true）。Redis故障 → fail-open启用。 */
    public boolean isEnabled(String rule) {
        try {
            Object v = redis.opsForHash().get(KEY_PREFIX + rule, F_ENABLED);
            return v == null || Boolean.parseBoolean(v.toString());
        } catch (Exception e) {
            return true;   // fail open — never stop detecting because Redis blipped
        }
    }

    /** 手动权重覆盖，未设置返回null（表示用自动权重）。 */
    public Double weightOverride(String rule) {
        try {
            Object v = redis.opsForHash().get(KEY_PREFIX + rule, F_WEIGHT);
            return v == null ? null : Double.valueOf(v.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 更新配置。enabled非空则设置；weightPresent为true时，weight非空则设为[0,1]夹紧值、
     * 为空则清除覆盖（恢复自动权重）。
     * Update config. enabled is set when non-null. When weightPresent is true: a non-null
     * weight is clamped to [0,1] and stored; a null weight clears the override (back to auto).
     */
    public void update(String rule, Boolean enabled, boolean weightPresent, Double weight) {
        if (!ruleNames.contains(rule)) {
            throw new IllegalArgumentException("Unknown rule: " + rule);
        }
        String key = KEY_PREFIX + rule;
        if (enabled != null) {
            redis.opsForHash().put(key, F_ENABLED, String.valueOf(enabled));
        }
        if (weightPresent) {
            if (weight == null) {
                redis.opsForHash().delete(key, F_WEIGHT);
            } else {
                double clamped = Math.max(0.0, Math.min(1.0, weight));
                redis.opsForHash().put(key, F_WEIGHT, String.valueOf(clamped));
            }
        }
        log.info("Rule config updated: {} enabled={} weight={}", rule, enabled,
                weightPresent ? String.valueOf(weight) : "(unchanged)");
    }
}
