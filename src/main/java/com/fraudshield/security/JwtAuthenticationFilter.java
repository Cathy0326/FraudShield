package com.fraudshield.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 每次HTTP请求执行一次的JWT过滤器
 * Runs once per request; extracts and validates the Bearer token.
 *
 * 处理流程 (Flow):
 *   1. 读取 Authorization header
 *   2. 提取 Bearer token
 *   3. 验证签名和有效期
 *   4. 写入 SecurityContextHolder（后续Spring Security用于鉴权）
 *   If no/invalid token → do nothing; Spring Security will return 401.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            if (tokenProvider.validateToken(token)) {
                String username = tokenProvider.getUsernameFromToken(token);
                String role     = tokenProvider.getRoleFromToken(token);

                // 将验证通过的用户写入安全上下文，Spring Security后续鉴权用
                // Set the authenticated principal in the context so downstream
                // security checks (@PreAuthorize, etc.) see a logged-in user
                var auth = new UsernamePasswordAuthenticationToken(
                        username,
                        null,
                        List.of(new SimpleGrantedAuthority(role))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        chain.doFilter(request, response);
    }
}
