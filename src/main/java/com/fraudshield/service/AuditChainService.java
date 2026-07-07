package com.fraudshield.service;

import com.fraudshield.model.ReviewAuditRecord;
import com.fraudshield.repository.ReviewAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 防篡改审计链 — 审核决定的哈希链（区块链的最小可用形态）
 * Tamper-evident audit chain: a hash chain over review decisions — the minimal
 * useful form of the blockchain idea, without any of the consensus machinery.
 *
 * <p>为什么需要它：审核决定是有法律含义的记录（谁、何时、判定了什么）。DB管理员
 * 或被入侵的应用可以UPDATE任何一行 —— 普通表约束防不住这个。哈希链让"改历史"
 * 变得可检测：每条记录的HMAC覆盖前一条的HMAC，改第k条会使k之后所有记录失配。
 * Why: review decisions are records with legal weight (who decided what, when).
 * A DBA or a compromised app can UPDATE any row — table constraints don't stop that.
 * The chain makes history-rewriting detectable: each record's HMAC covers the
 * previous record's HMAC, so altering record k breaks every record after it.
 *
 * <p>为什么用HMAC而不是纯SHA-256：纯哈希链攻击者改完数据可以从断点开始**重算
 * 整条链**，无密钥即可伪造。HMAC需要密钥才能重算 —— 拿到DB但没拿到应用密钥的
 * 攻击者无法修复被自己弄断的链。密钥从主加密密钥派生（与PII加密密钥不同用途
 * 不同派生，密钥不跨用途复用）。
 * Why HMAC rather than plain SHA-256: with a plain hash chain an attacker can
 * simply recompute the whole chain after editing — no secret needed. HMAC requires
 * the key, so an attacker with DB access but not the app key cannot repair the
 * chain they broke. The key is derived from the master encryption key with a
 * distinct label (no key reuse across purposes). Without a configured key the
 * chain degrades to plain SHA-256 (still catches casual edits) with a warning.
 */
@Service
public final class AuditChainService {

    private static final Logger log = LoggerFactory.getLogger(AuditChainService.class);
    private static final String GENESIS = "0".repeat(64);

    private final ReviewAuditRepository repository;
    private final SecretKeySpec hmacKey;   // null → plain SHA-256 fallback

    public AuditChainService(ReviewAuditRepository repository,
                             @Value("${fraudshield.encryption.key:}") String base64MasterKey) {
        this.repository = repository;
        if (base64MasterKey == null || base64MasterKey.isBlank()) {
            log.warn("No encryption key configured - audit chain will use plain SHA-256. "
                    + "Tampering is still detectable, but an attacker with DB access could "
                    + "recompute the chain. Set FRAUDSHIELD_ENCRYPTION_KEY for keyed hashing.");
            this.hmacKey = null;
        } else {
            byte[] master = Base64.getDecoder().decode(base64MasterKey);
            this.hmacKey = new SecretKeySpec(deriveKey(master, "audit"), "HmacSHA256");
        }
    }

    /**
     * 追加一环。synchronized：审核是低频人工操作，串行化保证链不分叉，
     * 比乐观锁重试简单得多。
     * Append one link. synchronized: reviews are low-frequency human actions, and
     * serializing appends guarantees the chain never forks — far simpler than
     * optimistic-lock retries for this write rate.
     */
    public synchronized ReviewAuditRecord append(String orderId, String decision, String reviewer) {
        String prevHash = repository.findTopByOrderByIdDesc()
                .map(ReviewAuditRecord::getRecordHash)
                .orElse(GENESIS);

        LocalDateTime now = LocalDateTime.now();
        ReviewAuditRecord record = ReviewAuditRecord.builder()
                .orderId(orderId)
                .decision(decision)
                .reviewer(reviewer)
                .decidedAt(now)
                .prevHash(prevHash)
                .recordHash(hash(payload(orderId, decision, reviewer, now, prevHash)))
                .build();
        return repository.save(record);
    }

    /**
     * 校验整条链：重算每条记录的哈希并核对链接关系。
     * 返回第一处断链的位置（-1表示完好）。
     * Verify the whole chain: recompute every hash and check the links.
     * Returns the index of the first broken record, or -1 when intact.
     */
    public Map<String, Object> verifyChain() {
        List<ReviewAuditRecord> records = repository.findAllByOrderByIdAsc();
        String expectedPrev = GENESIS;

        for (int i = 0; i < records.size(); i++) {
            ReviewAuditRecord r = records.get(i);
            boolean linkOk = expectedPrev.equals(r.getPrevHash());
            boolean hashOk = hash(payload(r.getOrderId(), r.getDecision(), r.getReviewer(),
                    r.getDecidedAt(), r.getPrevHash())).equals(r.getRecordHash());
            if (!linkOk || !hashOk) {
                return Map.of(
                        "valid", false,
                        "records", records.size(),
                        "firstBrokenIndex", i,
                        "brokenRecordId", r.getId());
            }
            expectedPrev = r.getRecordHash();
        }
        return Map.of("valid", true, "records", records.size(), "keyed", hmacKey != null);
    }

    public List<ReviewAuditRecord> getChain() {
        return repository.findAllByOrderByIdAsc();
    }

    // ── internals ────────────────────────────────────────────────────────────

    // 字段间用不可能出现在数据里的分隔符，防止字段拼接歧义（"a|bc" vs "a|b","c"）
    // Unambiguous field separator prevents concatenation collisions
    private String payload(String orderId, String decision, String reviewer,
                           LocalDateTime decidedAt, String prevHash) {
        return String.join("\u001F", orderId, decision, reviewer,
                String.valueOf(decidedAt), prevHash);
    }

    private String hash(String payload) {
        try {
            byte[] digest;
            if (hmacKey != null) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(hmacKey);
                digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            } else {
                digest = MessageDigest.getInstance("SHA-256")
                        .digest(payload.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Audit hash computation failed", e);
        }
    }

    private static byte[] deriveKey(byte[] masterKey, String label) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(masterKey);
            sha.update(label.getBytes(StandardCharsets.UTF_8));
            return sha.digest();
        } catch (Exception e) {
            throw new IllegalStateException("Audit key derivation failed", e);
        }
    }
}
