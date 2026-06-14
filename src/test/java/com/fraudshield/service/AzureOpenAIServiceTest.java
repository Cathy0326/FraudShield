package com.fraudshield.service;

import com.fraudshield.model.AiAnalysis;
import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

class AzureOpenAIServiceTest {

    private HttpClient mockHttpClient;
    private AzureOpenAIService service;

    private Order sampleOrder;
    private RiskResult sampleResult;

    @BeforeEach
    void setUp() {
        mockHttpClient = mock(HttpClient.class);
        service        = new AzureOpenAIService(mockHttpClient);

        // 注入@Value字段
        // Inject @Value fields via reflection (no Spring context needed)
        ReflectionTestUtils.setField(service, "endpoint",   "https://test.openai.azure.com/");
        ReflectionTestUtils.setField(service, "apiKey",     "test-key-123");
        ReflectionTestUtils.setField(service, "deployment", "gpt-4o");
        ReflectionTestUtils.setField(service, "aiEnabled",  true);

        sampleOrder = new Order("ORD-AI-001", "USER-001", 350.0,
                "1.2.3.4", "DEVICE-X", LocalDateTime.now());
        sampleResult = RiskResult.builder()
                .orderId("ORD-AI-001")
                .riskLevel(RiskLevel.MEDIUM)
                .riskScore(0.65)
                .triggeredRules(List.of("AbnormalAmountRule"))
                .explanation("Amount 3x above average")
                .build();
    }

    // ── Test 1: 成功解析Azure OpenAI响应 ─────────────────────────────────────
    // Happy path: well-formed JSON response from Azure OpenAI
    @SuppressWarnings("unchecked")
    @Test
    void analyze_successfulResponse_returnsAiEnhancedAnalysis() throws Exception {
        String azurePayload = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"aiRiskLevel\\":\\"MEDIUM\\",\\"confidence\\":0.78,\\"reasoning\\":\\"Unusual amount\\",\\"recommendation\\":\\"manual_review\\",\\"keyFactors\\":[\\"high_amount\\",\\"new_pattern\\"]}"
                    }
                  }]
                }
                """;

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(azurePayload);
        doReturn(mockResponse).when(mockHttpClient).send(any(), any());

        AiAnalysis result = service.analyze(sampleOrder, sampleResult);

        assertThat(result.isAiEnhanced()).isTrue();
        assertThat(result.getAiRiskLevel()).isEqualTo("MEDIUM");
        assertThat(result.getConfidence()).isEqualTo(0.78);
        assertThat(result.getReasoning()).isEqualTo("Unusual amount");
        assertThat(result.getRecommendation()).isEqualTo("manual_review");
        assertThat(result.getKeyFactors()).containsExactly("high_amount", "new_pattern");
    }

    // ── Test 2: AI服务返回非200状态码 → 降级 ──────────────────────────────────
    // Non-200 HTTP status → graceful fallback (aiEnhanced=false)
    @SuppressWarnings("unchecked")
    @Test
    void analyze_nonOkHttpStatus_returnsFallback() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(429);
        when(mockResponse.body()).thenReturn("{\"error\":\"rate limited\"}");
        doReturn(mockResponse).when(mockHttpClient).send(any(), any());

        AiAnalysis result = service.analyze(sampleOrder, sampleResult);

        assertThat(result.isAiEnhanced()).isFalse();
        assertThat(result.getAiRiskLevel()).isEqualTo("UNKNOWN");
    }

    // ── Test 3: 网络异常 → 降级 ───────────────────────────────────────────────
    // IOException from HttpClient → graceful fallback
    @Test
    void analyze_networkException_returnsFallback() throws Exception {
        when(mockHttpClient.send(any(), any())).thenThrow(new IOException("connection refused"));

        AiAnalysis result = service.analyze(sampleOrder, sampleResult);

        assertThat(result.isAiEnhanced()).isFalse();
        assertThat(result.getReasoning()).contains("AI analysis unavailable");
    }

    // ── Test 4: ai.enabled=false → 跳过调用 ─────────────────────────────────
    // ai.enabled=false — no HTTP call made, returns "disabled" fallback
    @Test
    void analyze_aiDisabled_skipsHttpCall() throws Exception {
        ReflectionTestUtils.setField(service, "aiEnabled", false);

        AiAnalysis result = service.analyze(sampleOrder, sampleResult);

        assertThat(result.isAiEnhanced()).isFalse();
        verify(mockHttpClient, never()).send(any(), any());
    }

    // ── Test 5: 凭证未配置 → 跳过调用 ────────────────────────────────────────
    // Blank endpoint → skip HTTP call (credentials not configured)
    @Test
    void analyze_blankEndpoint_skipsHttpCall() throws Exception {
        ReflectionTestUtils.setField(service, "endpoint", "");

        AiAnalysis result = service.analyze(sampleOrder, sampleResult);

        assertThat(result.isAiEnhanced()).isFalse();
        verify(mockHttpClient, never()).send(any(), any());
    }
}
