package com.fraudshield.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private static final String SECRET =
            "ZnJhdWRzaGllbGQtc2VjcmV0LWtleS0yMDI2LWhhY2thdGhvbg==";

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret",    SECRET);
        ReflectionTestUtils.setField(provider, "expirationMs", 86400000L);
    }

    @Test
    void generateToken_returnsNonEmptyString() {
        String token = provider.generateToken("admin", "ROLE_ADMIN");
        assertThat(token).isNotBlank();
    }

    @Test
    void getUsernameFromToken_returnsCorrectUsername() {
        String token = provider.generateToken("admin", "ROLE_ADMIN");
        assertThat(provider.getUsernameFromToken(token)).isEqualTo("admin");
    }

    @Test
    void getRoleFromToken_returnsCorrectRole() {
        String token = provider.generateToken("operator", "ROLE_OPERATOR");
        assertThat(provider.getRoleFromToken(token)).isEqualTo("ROLE_OPERATOR");
    }

    @Test
    void validateToken_trueForValidToken() {
        String token = provider.generateToken("admin", "ROLE_ADMIN");
        assertThat(provider.validateToken(token)).isTrue();
    }

    @Test
    void validateToken_falseForGarbageString() {
        assertThat(provider.validateToken("notavalidtoken")).isFalse();
    }

    @Test
    void validateToken_falseForExpiredToken() {
        // expirationMs = -1000 → token already expired before it was issued
        JwtTokenProvider expiredProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(expiredProvider, "jwtSecret",    SECRET);
        ReflectionTestUtils.setField(expiredProvider, "expirationMs", -1000L);

        String expiredToken = expiredProvider.generateToken("admin", "ROLE_ADMIN");
        assertThat(provider.validateToken(expiredToken)).isFalse();
    }
}
