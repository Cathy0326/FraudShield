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

    // 幂等性检查：根据orderId查找已存在的风险记录
    // Idempotency check: find an existing record for this orderId
    Optional<RiskEvent> findByOrderId(String orderId);
}
