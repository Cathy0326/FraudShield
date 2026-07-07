package com.fraudshield.repository;

import com.fraudshield.model.RiskEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    List<RiskEvent> findByUserIdOrderByDetectedAtDesc(String userId);

    // 遗留行的reviewStatus为NULL（该列加入前写入的数据），视同待审
    // Legacy rows written before the column existed have NULL status — treat as pending
    @Query("SELECT e FROM RiskEvent e WHERE e.reviewStatus = 'PENDING_REVIEW' "
            + "OR e.reviewStatus IS NULL ORDER BY e.detectedAt DESC")
    List<RiskEvent> findPendingReview();

    // Used to spot fraud rings: distinct accounts that have ordered from the same IP.
    List<RiskEvent> findByIpAddressOrderByDetectedAtDesc(String ipAddress);

    // ── 审核标注反哺检测 / review labels feeding back into detection ──────────
    Long countByUserIdAndReviewStatus(String userId, String reviewStatus);

    boolean existsByIpAddressAndReviewStatusAndUserIdNot(
            String ipAddress, String reviewStatus, String userId);
}
