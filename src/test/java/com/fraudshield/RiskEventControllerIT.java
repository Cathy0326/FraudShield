package com.fraudshield;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RiskEventControllerIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean StringRedisTemplate stringRedisTemplate;

    private String adminToken;
    private String operatorToken;

    private static final String TS = String.valueOf(System.nanoTime()).substring(10);

    @BeforeEach
    void setUp() throws Exception {
        adminToken    = obtainToken("itadmin_" + TS, "It@123456", "ROLE_ADMIN");
        operatorToken = obtainToken("itop_" + TS,    "It@123456", "ROLE_OPERATOR");
    }

    // ── Test 1: unauthenticated request → 401 ─────────────────────────────
    @Test
    void recentEvents_noToken_returns401() throws Exception {
        mvc.perform(get("/api/risk-events/recent"))
           .andExpect(status().isUnauthorized());
    }

    // ── Test 2: authenticated → 200 + JSON array ──────────────────────────
    @Test
    void recentEvents_authenticated_returns200() throws Exception {
        mvc.perform(get("/api/risk-events/recent")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    // ── Test 3: stats → 200 + expected fields ─────────────────────────────
    @Test
    void stats_authenticated_returnsExpectedFields() throws Exception {
        MvcResult result = mvc.perform(get("/api/risk-events/stats")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("totalOrders")).isTrue();
        assertThat(body.has("highRiskCount")).isTrue();
        assertThat(body.has("mediumRiskCount")).isTrue();
        assertThat(body.has("riskRate")).isTrue();
    }

    // ── Test 4: unknown orderId → 404 with error body ─────────────────────
    @Test
    void getByOrderId_notFound_returns404() throws Exception {
        mvc.perform(get("/api/risk-events/NONEXISTENT-ORDER-XYZ")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Not Found"));
    }

    // ── Test 5: DELETE as operator → 403 ──────────────────────────────────
    @Test
    void delete_operatorRole_returns403() throws Exception {
        mvc.perform(delete("/api/risk-events/9999")
                .header("Authorization", "Bearer " + operatorToken))
           .andExpect(status().isForbidden());
    }

    // ── Test 6: DELETE as admin → 404 (not 403) ───────────────────────────
    @Test
    void delete_adminRole_notForbidden() throws Exception {
        // record doesn't exist → 404, but crucially NOT 403
        mvc.perform(delete("/api/risk-events/9999")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound());
    }

    // ── Helper: register + login, return JWT ──────────────────────────────
    private String obtainToken(String username, String password, String role) throws Exception {
        mvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s","role":"%s"}
                        """.formatted(username, password, role)));

        MvcResult login = mvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, password)))
           .andExpect(status().isOk())
           .andReturn();

        return objectMapper.readTree(login.getResponse().getContentAsString())
                           .path("token").asText();
    }
}
