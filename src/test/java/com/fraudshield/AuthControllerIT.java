package com.fraudshield;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIT {

    @Autowired MockMvc mvc;

    @MockBean StringRedisTemplate stringRedisTemplate;

    // unique suffix per test run to avoid H2 conflicts when tests run repeatedly
    private static final String TS = String.valueOf(System.nanoTime()).substring(10);

    // ── Test 1: register new user ──────────────────────────────────────────
    @Test
    void register_newUser_returns200WithUsername() throws Exception {
        String body = """
                {"username":"ituser_%s","password":"It@123456","role":"ROLE_ADMIN"}
                """.formatted(TS + "1");

        mvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.username").exists());
    }

    // ── Test 2: login with correct credentials returns JWT ─────────────────
    @Test
    void login_correctCredentials_returnsToken() throws Exception {
        String user = "itlogin_" + TS;
        // register first
        mvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"It@123456","role":"ROLE_ADMIN"}
                        """.formatted(user))).andExpect(status().isOk());

        // then login
        mvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"It@123456"}
                        """.formatted(user)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.token").isNotEmpty())
           .andExpect(jsonPath("$.role").value("ROLE_ADMIN"));
    }

    // ── Test 3: login with wrong password ─────────────────────────────────
    @Test
    void login_wrongPassword_returnsError() throws Exception {
        mvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"admin","password":"wrongpassword"}
                        """))
           .andExpect(status().is4xxClientError());
    }

    // ── Test 4: login with unknown user ───────────────────────────────────
    @Test
    void login_unknownUser_returnsError() throws Exception {
        mvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"nobody_xyz","password":"pass"}
                        """))
           .andExpect(status().is4xxClientError());
    }

    // ── Test 5: duplicate username registration fails ──────────────────────
    @Test
    void register_duplicateUsername_returns4xx() throws Exception {
        String user = "itdup_" + TS;
        String body = """
                {"username":"%s","password":"It@123456","role":"ROLE_OPERATOR"}
                """.formatted(user);

        mvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk());

        mvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
           .andExpect(status().is4xxClientError());
    }
}
