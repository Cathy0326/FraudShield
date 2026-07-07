package com.fraudshield.repository;

import com.fraudshield.model.ReviewAuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewAuditRepository extends JpaRepository<ReviewAuditRecord, Long> {

    Optional<ReviewAuditRecord> findTopByOrderByIdDesc();

    List<ReviewAuditRecord> findAllByOrderByIdAsc();
}
