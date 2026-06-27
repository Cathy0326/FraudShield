package com.fraudshield.controller;

import com.fraudshield.dto.LoginRequest;
import com.fraudshield.dto.LoginResponse;
import com.fraudshield.dto.RegisterRequest;
import com.fraudshield.model.AppUser;
import com.fraudshield.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request.getUsername(), request.getPassword());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@RequestBody RegisterRequest request) {
        if (ADMIN_ROLE.equals(request.getRole()) && !callerIsAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only an existing admin can register a new admin account");
        }
        AppUser user = authService.register(
                request.getUsername(), request.getPassword(), request.getRole());
        return ResponseEntity.ok(Map.of(
                "message",  "User registered successfully",
                "username", user.getUsername()
        ));
    }

    // /auth/register is publicly reachable, but JwtAuthenticationFilter still populates the
    // SecurityContext when a valid Bearer token is present — so a logged-in admin's request
    // is distinguishable from an anonymous self-signup here.
    private boolean callerIsAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ADMIN_ROLE.equals(a.getAuthority()));
    }
}
