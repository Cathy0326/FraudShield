package com.fraudshield.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudshield.model.AiAnalysis;
import com.fraudshield.model.Order;
import com.fraudshield.model.RiskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Azure OpenAI集成服务 — 对MEDIUM风险订单进行LLM二次分析
 * Calls the Azure OpenAI chat completions API to enrich medium-risk orders with
 * AI-generated reasoning and recommendations.
 *
 * 设计要点 (Design notes):
 *  - HttpClient可通过构造器注入（测试时可传入mock）
 *  - 所有网络错误都有优雅降级：返回aiEnhanced=false的AiAnalysis
 *  - ai.enabled=false时直接跳过调用（本地开发无需配置密钥）
 */
@Service
public class AzureOpenAIService {

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAIService.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${azure.openai.endpoint:}")
    private String endpoint;

    @Value("${azure.openai.key:}")
    private String apiKey;

    @Value("${azure.openai.deployment:gpt-4o}")
    private String deployment;

    @Value("${ai.enabled:true}")
    private boolean aiEnabled;

    // 主构造器：生产环境使用默认HttpClient
    // Primary constructor — production uses a shared HttpClient with 10s timeout
    public AzureOpenAIService() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    // 测试构造器：允许注入mock HttpClient
    // Test constructor — allows injecting a mock/stub HttpClient
    public AzureOpenAIService(HttpClient httpClient) {
        this.httpClient   = httpClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 分析MEDIUM风险订单 — 返回AI增强分析结果
     * Analyzes a medium-risk order. Never throws — returns aiEnhanced=false on any error.
     */
    public AiAnalysis analyze(Order order, RiskResult riskResult) {
        if (!aiEnabled || endpoint.isBlank() || apiKey.isBlank()) {
            log.debug("AI analysis skipped (aiEnabled={}, endpoint configured={})",
                    aiEnabled, !endpoint.isBlank());
            return buildFallback("AI analysis disabled or not configured");
        }

        try {
            String prompt = buildPrompt(order, riskResult);
            String responseBody = callAzureOpenAI(prompt);
            return parseResponse(responseBody);
        } catch (Exception e) {
            log.warn("AI analysis failed for orderId={}: {}", order.getOrderId(), e.getMessage());
            return buildFallback("AI analysis unavailable: " + e.getMessage());
        }
    }

    // ── Private helpers ──────────────────────────────

    private String buildPrompt(Order order, RiskResult riskResult) {
        return String.format("""
                You are a fraud detection AI. Analyze the following order and provide a structured risk assessment.

                Order Details:
                - Order ID: %s
                - User ID: %s
                - Amount: $%.2f
                - IP Address: %s
                - Device ID: %s
                - Timestamp: %s

                Rule Engine Assessment:
                - Risk Level: %s
                - Risk Score: %.2f
                - Triggered Rules: %s
                - Explanation: %s

                Respond in JSON format with exactly these fields:
                {
                  "aiRiskLevel": "HIGH|MEDIUM|LOW|NORMAL",
                  "confidence": 0.0-1.0,
                  "reasoning": "brief explanation",
                  "recommendation": "block|manual_review|monitor|allow",
                  "keyFactors": ["factor1", "factor2"]
                }
                """,
                order.getOrderId(), order.getUserId(), order.getAmount(),
                order.getIpAddress(), order.getDeviceId(), order.getTimestamp(),
                riskResult.getRiskLevel(), riskResult.getRiskScore(),
                riskResult.getTriggeredRules(), riskResult.getExplanation());
    }

    private String callAzureOpenAI(String prompt) throws Exception {
        // Unified Azure OpenAI v1 inference API — OpenAI-compatible chat completions.
        // endpoint must already include the /openai/v1 path segment, e.g.
        // https://<resource>.services.ai.azure.com/openai/v1
        String url = endpoint.replaceAll("/$", "") + "/chat/completions";

        String requestBody = objectMapper.writeValueAsString(java.util.Map.of(
                "model", deployment,
                "messages", List.of(
                        java.util.Map.of("role", "user", "content", prompt)
                ),
                "max_tokens", 500,
                "temperature", 0.3
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("api-key", apiKey)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Azure OpenAI returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        return response.body();
    }

    private AiAnalysis parseResponse(String responseBody) throws Exception {
        JsonNode root    = objectMapper.readTree(responseBody);
        // 提取 choices[0].message.content 中的JSON字符串
        // Extract the assistant's message content from choices[0].message.content
        String content   = root.path("choices").get(0)
                               .path("message").path("content").asText();

        // 去掉可能存在的markdown代码块标记
        // Strip markdown fences if the model wrapped its JSON in ```json ... ```
        content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        JsonNode parsed  = objectMapper.readTree(content);

        List<String> keyFactors = new ArrayList<>();
        JsonNode factorsNode    = parsed.path("keyFactors");
        if (factorsNode.isArray()) {
            factorsNode.forEach(n -> keyFactors.add(n.asText()));
        }

        return AiAnalysis.builder()
                .aiRiskLevel(parsed.path("aiRiskLevel").asText("MEDIUM"))
                .confidence(parsed.path("confidence").asDouble(0.5))
                .reasoning(parsed.path("reasoning").asText(""))
                .recommendation(parsed.path("recommendation").asText("manual_review"))
                .keyFactors(keyFactors)
                .aiEnhanced(true)
                .build();
    }

    private AiAnalysis buildFallback(String reason) {
        return AiAnalysis.builder()
                .aiRiskLevel("UNKNOWN")
                .confidence(0.0)
                .reasoning(reason)
                .recommendation("manual_review")
                .keyFactors(List.of())
                .aiEnhanced(false)
                .build();
    }
}
