package com.fraudshield.service;

import com.fraudshield.model.ReviewAuditRecord;
import com.fraudshield.repository.ReviewAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AuditChainServiceTest {

    private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Mock ReviewAuditRepository repository;

    private AuditChainService service;
    private final List<ReviewAuditRecord> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new AuditChainService(repository, TEST_KEY);
        stored.clear();
        // 用内存列表模拟追加与读取 / in-memory list stands in for the DB
        lenient().when(repository.save(any(ReviewAuditRecord.class))).thenAnswer(inv -> {
            ReviewAuditRecord r = inv.getArgument(0);
            r.setId((long) (stored.size() + 1));
            stored.add(r);
            return r;
        });
        lenient().when(repository.findTopByOrderByIdDesc()).thenAnswer(inv ->
                stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(stored.size() - 1)));
        lenient().when(repository.findAllByOrderByIdAsc()).thenAnswer(inv -> new ArrayList<>(stored));
    }

    @Test
    void firstRecord_linksToGenesis() {
        ReviewAuditRecord r = service.append("ORD-1", "CONFIRMED_FRAUD", "admin");

        assertThat(r.getPrevHash()).isEqualTo("0".repeat(64));
        assertThat(r.getRecordHash()).hasSize(64).isNotEqualTo(r.getPrevHash());
    }

    @Test
    void subsequentRecords_linkToPredecessor() {
        ReviewAuditRecord first  = service.append("ORD-1", "CONFIRMED_FRAUD", "admin");
        ReviewAuditRecord second = service.append("ORD-2", "APPROVED", "operator");

        assertThat(second.getPrevHash()).isEqualTo(first.getRecordHash());
    }

    @Test
    void intactChain_verifiesValid() {
        service.append("ORD-1", "CONFIRMED_FRAUD", "admin");
        service.append("ORD-2", "FALSE_POSITIVE", "operator");
        service.append("ORD-3", "APPROVED", "admin");

        Map<String, Object> result = service.verifyChain();

        assertThat(result.get("valid")).isEqualTo(true);
        assertThat(result.get("records")).isEqualTo(3);
        assertThat(result.get("keyed")).isEqualTo(true);
    }

    @Test
    void tamperedRecord_isDetectedAtExactPosition() {
        service.append("ORD-1", "CONFIRMED_FRAUD", "admin");
        service.append("ORD-2", "CONFIRMED_FRAUD", "admin");
        service.append("ORD-3", "APPROVED", "admin");

        // 攻击场景：DBA直接UPDATE第二条，把确认欺诈改成放行
        // Attack: a DBA UPDATEs record 2, flipping confirmed fraud to approved
        stored.get(1).setDecision("APPROVED");

        Map<String, Object> result = service.verifyChain();

        assertThat(result.get("valid")).isEqualTo(false);
        assertThat(result.get("firstBrokenIndex")).isEqualTo(1);
        assertThat(result.get("brokenRecordId")).isEqualTo(2L);
    }

    @Test
    void deletedRecord_breaksTheLink() {
        service.append("ORD-1", "CONFIRMED_FRAUD", "admin");
        service.append("ORD-2", "CONFIRMED_FRAUD", "admin");
        service.append("ORD-3", "APPROVED", "admin");

        // 攻击场景：删除中间一条记录"抹掉"某次决定
        // Attack: deleting a middle record to erase a decision
        stored.remove(1);

        Map<String, Object> result = service.verifyChain();

        assertThat(result.get("valid")).isEqualTo(false);
        assertThat(result.get("firstBrokenIndex")).isEqualTo(1);
    }

    @Test
    void emptyChain_isValid() {
        assertThat(service.verifyChain().get("valid")).isEqualTo(true);
    }

    @Test
    void unkeyedMode_stillDetectsTampering() {
        // 无密钥降级为SHA-256：仍能发现随手篡改（防不住带重算的攻击，已在文档说明）
        // Unkeyed fallback still catches casual edits (recompute attacks documented)
        AuditChainService unkeyed = new AuditChainService(repository, "");
        unkeyed.append("ORD-1", "CONFIRMED_FRAUD", "admin");
        stored.get(0).setReviewer("someone-else");

        assertThat(unkeyed.verifyChain().get("valid")).isEqualTo(false);
    }
}
