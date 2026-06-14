package com.fraudshield.service;

import com.fraudshield.dto.LoginResponse;
import com.fraudshield.model.AppUser;
import com.fraudshield.repository.AppUserRepository;
import com.fraudshield.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppUserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public AuthService(AppUserRepository userRepository,
                       BCryptPasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider) {
        this.userRepository  = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider   = tokenProvider;
    }

    /**
     * 用户登录 — 验证密码后签发JWT
     * Login: verify credentials then issue a JWT.
     */
    public LoginResponse login(String username, String password) {
        log.info("Login attempt for username={}", username);

        AppUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.warn("Login failed — user not found: {}", username);
                    return new RuntimeException("User not found: " + username);
                });

        if (!passwordEncoder.matches(password, user.getPassword())) {
            log.warn("Login failed — wrong password for username={}", username);
            throw new RuntimeException("Invalid password");
        }

        String token = tokenProvider.generateToken(user.getUsername(), user.getRole());
        log.info("Login successful for username={}, role={}", username, user.getRole());

        return new LoginResponse(token, user.getUsername(), user.getRole(), 86400000L);
    }

    /**
     * 注册新用户 — 密码BCrypt加密后存储
     * Register: hash the password and persist the new user.
     */
    public AppUser register(String username, String password, String role) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new RuntimeException("Username already taken: " + username);
        }

        AppUser user = AppUser.builder()
                .username(username)
                .password(passwordEncoder.encode(password))  // never store plaintext
                .role(role)
                .build();

        AppUser saved = userRepository.save(user);
        log.info("Registered new user: username={}, role={}", username, role);
        return saved;
    }
}
