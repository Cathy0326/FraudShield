package com.fraudshield.controller;

import com.fraudshield.dto.LoginRequest;
import com.fraudshield.dto.LoginResponse;
import com.fraudshield.dto.RegisterRequest;
import com.fraudshield.model.AppUser;
import com.fraudshield.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

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
        // TODO: restrict ROLE_ADMIN registration to existing admins in production
        AppUser user = authService.register(
                request.getUsername(), request.getPassword(), request.getRole());
        return ResponseEntity.ok(Map.of(
                "message",  "User registered successfully",
                "username", user.getUsername()
        ));
    }
}
