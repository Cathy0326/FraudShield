package com.fraudshield.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;   // stored as BCrypt hash, never plaintext

    private String role;       // "ROLE_ADMIN" or "ROLE_OPERATOR"

    @CreationTimestamp
    private LocalDateTime createdAt;
}
