package com.fraudshield.controller;

import com.fraudshield.dto.DashboardStatsDTO;
import com.fraudshield.dto.RiskEventDTO;
import com.fraudshield.model.AiAnalysis;
import com.fraudshield.model.Order;
import com.fraudshield.model.RiskResult;
import com.fraudshield.service.AzureOpenAIService;
import com.fraudshield.service.RiskEventService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/risk-events")
@CrossOrigin(origins = "http://localhost:3000")
public class RiskEventController {

    private final RiskEventService riskEventService;
    private final AzureOpenAIService aiService;

    public RiskEventController(RiskEventService riskEventService, AzureOpenAIService aiService) {
        this.riskEventService = riskEventService;
        this.aiService        = aiService;
    }

    // GET /api/risk-events/recent?limit=10
    @GetMapping("/recent")
    public ResponseEntity<List<RiskEventDTO>> getRecentEvents(
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(riskEventService.getRecentEvents(limit));
    }

    // GET /api/risk-events?riskLevel=HIGH  (optional filter)
    @GetMapping
    public ResponseEntity<List<RiskEventDTO>> getEvents(
            @RequestParam(required = false) String riskLevel) {
        if (riskLevel != null && !riskLevel.isBlank()) {
            return ResponseEntity.ok(riskEventService.getEventsByRiskLevel(riskLevel));
        }
        return ResponseEntity.ok(riskEventService.getRecentEvents(50));
    }

    // GET /api/risk-events/stats
    @GetMapping("/stats")
    public ResponseEntity<DashboardStatsDTO> getStats() {
        return ResponseEntity.ok(riskEventService.getDashboardStats());
    }

    // GET /api/risk-events/{orderId}
    @GetMapping("/{orderId}")
    public ResponseEntity<RiskEventDTO> getByOrderId(@PathVariable String orderId) {
        return ResponseEntity.ok(riskEventService.getEventByOrderId(orderId));
    }

    // GET /api/risk-events/{orderId}/ai-analysis — on-demand AI analysis for any stored event
    @GetMapping("/{orderId}/ai-analysis")
    public ResponseEntity<AiAnalysis> getAiAnalysis(@PathVariable String orderId) {
        RiskEventDTO event = riskEventService.getEventByOrderId(orderId);
        // 重新构造最简Order和RiskResult传给AI服务
        // Reconstruct minimal Order + RiskResult to pass into the AI service
        Order order = new Order(event.getOrderId(), event.getUserId(), event.getAmount(),
                event.getIpAddress(), "", null);
        RiskResult riskResult = RiskResult.builder()
                .orderId(event.getOrderId())
                .riskLevel(com.fraudshield.model.RiskLevel.valueOf(event.getRiskLevel()))
                .riskScore(event.getRiskScore())
                .triggeredRules(event.getTriggeredRules())
                .explanation(event.getExplanation())
                .build();
        return ResponseEntity.ok(aiService.analyze(order, riskResult));
    }

    // DELETE /api/risk-events/{id} — ROLE_ADMIN only
    // 细粒度鉴权：URL级别要求认证（SecurityConfig），方法级别要求ADMIN（@PreAuthorize）
    // Fine-grained auth: SecurityConfig = "logged in?", @PreAuthorize = "right role?"
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> deleteEvent(@PathVariable Long id) {
        riskEventService.deleteEvent(id);
        return ResponseEntity.ok(Map.of("message", "deleted"));
    }
}
