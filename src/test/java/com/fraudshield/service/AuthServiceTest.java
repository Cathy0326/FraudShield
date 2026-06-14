package com.fraudshield.service;

import com.fraudshield.dto.LoginResponse;
import com.fraudshield.model.AppUser;
import com.fraudshield.repository.AppUserRepository;
import com.fraudshield.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock AppUserRepository userRepository;
    @Mock BCryptPasswordEncoder passwordEncoder;
    @Mock JwtTokenProvider tokenProvider;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, tokenProvider);
    }

    private AppUser user(String username, String hashedPw, String role) {
        return AppUser.builder().username(username).password(hashedPw).role(role).build();
    }

    @Test
    void login_correctCredentials_returnsLoginResponse() {
        when(userRepository.findByUsername("admin"))
                .thenReturn(Optional.of(user("admin", "hashed", "ROLE_ADMIN")));
        when(passwordEncoder.matches("Admin@123", "hashed")).thenReturn(true);
        when(tokenProvider.generateToken("admin", "ROLE_ADMIN")).thenReturn("jwt-token");

        LoginResponse response = authService.login("admin", "Admin@123");

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getUsername()).isEqualTo("admin");
        assertThat(response.getRole()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void login_wrongPassword_throwsRuntimeException() {
        when(userRepository.findByUsername("admin"))
                .thenReturn(Optional.of(user("admin", "hashed", "ROLE_ADMIN")));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("admin", "wrong"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid password");
    }

    @Test
    void login_unknownUsername_throwsRuntimeException() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("ghost", "pass"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void register_newUser_savesWithHashedPassword() {
        when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Pass@123")).thenReturn("bcrypt-hash");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppUser saved = authService.register("newuser", "Pass@123", "ROLE_OPERATOR");

        assertThat(saved.getPassword()).isEqualTo("bcrypt-hash");
        assertThat(saved.getUsername()).isEqualTo("newuser");
        verify(userRepository).save(any());
    }

    @Test
    void register_duplicateUsername_throwsRuntimeException() {
        when(userRepository.findByUsername("admin"))
                .thenReturn(Optional.of(user("admin", "h", "ROLE_ADMIN")));

        assertThatThrownBy(() -> authService.register("admin", "pass", "ROLE_ADMIN"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Username already taken");
    }
}
