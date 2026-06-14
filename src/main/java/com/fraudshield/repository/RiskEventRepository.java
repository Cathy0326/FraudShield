package com.fraudshield.repository;

import com.fraudshield.model.RiskEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RiskEventRepository extends JpaRepository<RiskEvent, Long> {

    List<RiskEvent> findByRiskLevelOrderByDetectedAtDesc(String riskLevel);

    List<RiskEvent> findTop10ByOrderByDetectedAtDesc();

    Long countByRiskLevel(String riskLevel);

    List<RiskEvent> findByDetectedAtBetween(LocalDateTime start, LocalDateTime end);

    Optional<RiskEvent> findByOrderId(String orderId);

    List<RiskEvent> findByRiskLevelAndDetectedAtBetween(
            String riskLevel, LocalDateTime start, LocalDateTime end);
}
