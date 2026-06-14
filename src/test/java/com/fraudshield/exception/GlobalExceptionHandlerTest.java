package com.fraudshield.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Standalone MockMvc test — no Spring Security slice needed.
 * We wire a tiny throwable controller directly so the handler is exercised
 * without loading the full application context.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    /** Minimal controller that throws on demand for testing the handler. */
    @RestController
    static class ThrowingController {
        @GetMapping("/test-not-found")
        public void notFound() { throw new ResourceNotFoundException("Order ORD-999 not found"); }

        @GetMapping("/test-bad-request")
        public void badRequest() { throw new RuntimeException("invalid input"); }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void resourceNotFoundException_returns404WithBody() throws Exception {
        mockMvc.perform(get("/test-not-found").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Order ORD-999 not found"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void runtimeException_returns400WithBody() throws Exception {
        mockMvc.perform(get("/test-bad-request").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("invalid input"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
