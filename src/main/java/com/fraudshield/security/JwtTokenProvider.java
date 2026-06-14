package com.fraudshield.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

/**
 * JWT工具类 — 签发、解析、校验Token
 * JWT utility: issue, parse and validate tokens.
 *
 * JWT结构 (3 dot-separated base64 sections):
 *   Header.Payload.Signature
 *   Header:    {"alg":"HS256","typ":"JWT"}
 *   Payload:   {"sub":"admin","role":"ROLE_ADMIN","iat":...,"exp":...}
 *   Signature: HMAC-SHA256(header + "." + payload, secret)
 *
 * 无状态原理 (Why stateless):
 *   服务器不存储session，只需验证签名和有效期。
 *   Any server instance can validate without shared state — just needs the secret.
 */
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration-ms}")
    private long expirationMs;

    private SecretKey signingKey() {
        // base64解码后作为HMAC-SHA256签名密钥
        // Decode the base64 secret into raw bytes for HMAC-SHA256
        byte[] keyBytes = Base64.getDecoder().decode(jwtSecret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成JWT Token — 包含用户名和角色
     * Generate a signed JWT containing username and role claims.
     */
    public String generateToken(String username, String role) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey())
                .compact();
    }

    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    public String getRoleFromToken(String token) {
        return (String) parseClaims(token).get("role");
    }

    /**
     * 校验Token — 过期或格式错误返回false，不抛异常
     * Validate token: returns false for expired/malformed; never throws.
     * Filter callers should not have to deal with exceptions.
     */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
