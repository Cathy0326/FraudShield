package com.fraudshield.controller;

import com.fraudshield.dto.RuleConfigDTO;
import com.fraudshield.dto.RulePrecisionDTO;
import com.fraudshield.service.RiskEventService;
import com.fraudshield.service.RuleConfigService;
import com.fraudshield.service.RuleWeightService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 规则调优台 — 诊断→操作闭环的"操作"端。
 * Rule-tuning console: the "act" end of the diagnose→act loop.
 *
 * <p>读接口对所有登录用户开放（谁都能看规则表现）；写接口仅ADMIN —— 调权重/停用规则
 * 直接改变生产打分，是受控操作。
 * Reads are open to any authenticated user (anyone may see how rules perform); writes
 * are ADMIN-only, since changing a weight or disabling a rule alters production scoring.
 */
@RestController
@RequestMapping("/api/rules")
public class RuleConfigController {

    private final RuleConfigService ruleConfig;
    private final RuleWeightService ruleWeights;
    private final RiskEventService riskEventService;

    public RuleConfigController(RuleConfigService ruleConfig, RuleWeightService ruleWeights,
                                RiskEventService riskEventService) {
        this.ruleConfig = ruleConfig;
        this.ruleWeights = ruleWeights;
        this.riskEventService = riskEventService;
    }

    // GET /api/rules/config — every rule with its config joined to its measured precision
    @GetMapping("/config")
    public ResponseEntity<List<RuleConfigDTO>> getConfig() {
        Map<String, RulePrecisionDTO> precision = riskEventService.getRulePrecision().stream()
                .collect(Collectors.toMap(RulePrecisionDTO::getRule, p -> p, (a, b) -> a));

        List<RuleConfigDTO> rows = ruleConfig.ruleNames().stream()
                .map(rule -> toDto(rule, precision.get(rule)))
                .collect(Collectors.toList());
        return ResponseEntity.ok(rows);
    }

    // PUT /api/rules/config/{rule} — { "enabled": bool?, "weight": number|null? }
    // enabled 缺省不改；weight 缺省不改，显式null清除覆盖（恢复自动权重）
    // enabled absent = unchanged; weight absent = unchanged, explicit null clears the override
    @PutMapping("/config/{rule}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RuleConfigDTO> updateConfig(
            @PathVariable String rule, @RequestBody Map<String, Object> body) {

        Boolean enabled = body.get("enabled") instanceof Boolean b ? b : null;
        boolean weightPresent = body.containsKey("weight");
        Double weight = body.get("weight") instanceof Number n ? n.doubleValue() : null;

        ruleConfig.update(rule, enabled, weightPresent, weight);

        RulePrecisionDTO p = riskEventService.getRulePrecision().stream()
                .filter(r -> r.getRule().equals(rule)).findFirst().orElse(null);
        return ResponseEntity.ok(toDto(rule, p));
    }

    private RuleConfigDTO toDto(String rule, RulePrecisionDTO p) {
        Double override = ruleConfig.weightOverride(rule);
        double auto = ruleWeights.weightFor(rule);
        return RuleConfigDTO.builder()
                .rule(rule)
                .enabled(ruleConfig.isEnabled(rule))
                .weightOverride(override)
                .autoWeight(Math.round(auto * 100.0) / 100.0)
                .effectiveWeight(Math.round((override != null ? override : auto) * 100.0) / 100.0)
                .precision(p == null ? null : p.getPrecision())
                .totalHits(p == null ? 0 : p.getTotalHits())
                .reviewedHits(p == null ? 0 : p.getReviewedHits())
                .build();
    }
}
