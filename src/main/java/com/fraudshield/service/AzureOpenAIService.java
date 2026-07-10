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
                  "confidence": <a single decimal number between 0.0 and 1.0, e.g. 0.85>,
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

    /**
     * Endpoint归一化 — 容错Azure Portal复制出来的各种格式
     * Normalizes whatever endpoint format was pasted from the Azure Portal.
     *
     * <p>Portal的"Keys and Endpoint"页给的是裸域名（https://x.openai.azure.com/ 或
     * https://x.services.ai.azure.com/），而统一v1推理API要求路径含/openai/v1。
     * 用户直接粘贴Portal的值会打到错误URL，Azure返回401 "invalid subscription key
     * or wrong API endpoint" —— 报错指向key，真凶是路径。这里自动补全，两种格式都能用。
     * The Portal's "Keys and Endpoint" page gives a bare domain, but the unified v1
     * inference API needs the /openai/v1 path. Pasting the Portal value verbatim hits
     * the wrong URL and Azure answers 401 blaming the key — the real culprit is the
     * path. Auto-appending makes both formats work.
     */
    static String normalizeEndpoint(String raw) {
        String base = raw.trim().replaceAll("/+$", "");
        if (!base.contains("/openai")) {
            base = base + "/openai/v1";
        }
        return base;
    }

    private String callAzureOpenAI(String prompt) throws Exception {
        // Unified Azure OpenAI v1 inference API — OpenAI-compatible chat completions.
        String url = normalizeEndpoint(endpoint) + "/chat/completions";

        // response_format=json_object：模型层保证输出合法JSON —— 修复偶发的
        // "LLM自己写坏JSON"导致的解析失败（如 confidence: 0. 后跟空格）。
        // JSON mode guarantees syntactically valid JSON at the model layer,
        // fixing the intermittent parse failures caused by the LLM itself
        // emitting malformed JSON (e.g. a dangling decimal point).
        String requestBody = objectMapper.writeValueAsString(java.util.Map.of(
                "model", deployment,
                "messages", List.of(
                        java.util.Map.of("role", "user", "content", prompt)
                ),
                "response_format", java.util.Map.of("type", "json_object"),
                "max_tokens", 500,
                "temperature", 0.3
        ));

        // 同时带两种认证头：v1 API收Bearer，旧式deployments API收api-key —— 两者兼容
        // Send both auth headers: the v1 API accepts Bearer, the legacy API api-key
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("api-key", apiKey)
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            // URL入报错信息（key绝不入）—— "wrong endpoint"类401靠它一眼定位
            // Include the target URL (never the key) so endpoint-shaped 401s are
            // diagnosable from the fallback reason alone.
            throw new RuntimeException("Azure OpenAI returned HTTP " + response.statusCode()
                    + " from " + url + ": " + response.body());
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

        // 防御性截取：即使模型在JSON前后加了闲话，也只取首个{到最后一个}之间
        // Defensive slice: even if the model added chatter around the JSON,
        // parse only from the first '{' to the last '}'.
        int firstBrace = content.indexOf('{');
        int lastBrace  = content.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            content = content.substring(firstBrace, lastBrace + 1);
        }

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
