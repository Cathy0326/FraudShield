package com.fraudshield.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * PII字段加密器 — AES-256-GCM确定性加密
 * Field-level PII encryption: deterministic AES-256-GCM.
 *
 * <p>为什么是确定性加密（synthetic IV）而不是随机IV：
 * userId和ipAddress是查询条件（findByUserId、黑名单IP关联等）。随机IV下同一明文
 * 每次加密结果不同，所有等值查询全部失效。确定性加密（IV由明文的HMAC派生）保证
 * 同一明文 → 同一密文，JPA的等值查询经过converter后透明可用。
 *
 * <p>Why deterministic (synthetic-IV) encryption rather than a random IV per value:
 * userId and ipAddress are query keys (findByUserId, shared-IP linkage, etc.). With a
 * random IV the same plaintext encrypts differently every time, breaking every equality
 * query. Deriving the IV from an HMAC of the plaintext guarantees same plaintext → same
 * ciphertext, so JPA equality queries keep working transparently through the converter.
 *
 * <p>明确的取舍（documented trade-off）: deterministic encryption reveals equality
 * patterns — an attacker with DB access can see that two rows share a userId, but not
 * what the userId is. That's exactly the property the application itself relies on,
 * and the accepted industry pattern for searchable encrypted columns (e.g. SQL Server
 * Always Encrypted's deterministic mode, AWS DynamoDB Encryption Client).
 *
 * <p>密钥来自环境变量（32字节Base64）。未配置时降级为明文直通并打警告 ——
 * 本地开发无需配置即可跑通，生产部署必须设置。
 * Key comes from the environment (base64, 32 bytes). When unset the encryptor degrades
 * to plaintext passthrough with a warning — local dev works with zero config, but any
 * real deployment must set the key.
 */
// final：构造器会因非法密钥抛异常，final类不可被子类化，杜绝finalizer攻击拿到半初始化实例
// final because the constructor throws on an invalid key — a final class can't be
// subclassed, which closes the finalizer-attack path to a partially initialized instance.
@Component
public final class FieldEncryptor {

    private static final Logger log = LoggerFactory.getLogger(FieldEncryptor.class);

    // 密文前缀用于区分密文与遗留明文行 / prefix distinguishes ciphertext from legacy plaintext rows
    private static final String PREFIX = "enc:v1:";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKeySpec aesKey;   // null → passthrough mode
    private final SecretKeySpec macKey;

    public FieldEncryptor(@Value("${fraudshield.encryption.key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("FRAUDSHIELD_ENCRYPTION_KEY not set - PII fields will be stored in PLAINTEXT. "
                    + "Set a 32-byte base64 key for any non-dev deployment.");
            this.aesKey = null;
            this.macKey = null;
            return;
        }
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != 32) {
            throw new IllegalStateException(
                    "fraudshield.encryption.key must decode to exactly 32 bytes, got " + key.length);
        }
        // 加密与IV派生使用不同密钥（同钥复用是密码学反模式）
        // Separate keys for encryption and IV derivation - reusing one key for two
        // purposes is a cryptographic anti-pattern. Derive both from the master key.
        this.aesKey = new SecretKeySpec(hkdfLike(key, "aes"), "AES");
        this.macKey = new SecretKeySpec(hkdfLike(key, "mac"), "HmacSHA256");
    }

    public boolean isEnabled() {
        return aesKey != null;
    }

    /** 加密；未配置密钥时原样返回 / encrypt, or pass through when no key is configured. */
    public String encrypt(String plaintext) {
        if (plaintext == null || aesKey == null) {
            return plaintext;
        }
        try {
            byte[] iv = syntheticIv(plaintext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // 加密失败绝不能静默落明文 / never silently fall back to plaintext on failure
            throw new IllegalStateException("PII encryption failed", e);
        }
    }

    /**
     * 解密；无前缀的值视为遗留明文行原样返回（该列加密前写入的数据）
     * Decrypt; values without the prefix are legacy plaintext rows (written before
     * encryption was enabled) and are returned as-is.
     */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (aesKey == null) {
            throw new IllegalStateException(
                    "Encrypted value found but no encryption key configured - set FRAUDSHIELD_ENCRYPTION_KEY");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv  = Arrays.copyOfRange(all, 0, GCM_IV_BYTES);
            byte[] ct  = Arrays.copyOfRange(all, GCM_IV_BYTES, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("PII decryption failed - wrong key or corrupted data", e);
        }
    }

    // IV = HMAC-SHA256(macKey, plaintext)前12字节 → 确定性且不泄露明文
    // Synthetic IV: first 12 bytes of HMAC-SHA256(macKey, plaintext) - deterministic
    // without revealing anything about the plaintext.
    private byte[] syntheticIv(String plaintext) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(macKey);
        return Arrays.copyOf(mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), GCM_IV_BYTES);
    }

    // 简化HKDF：SHA-256(masterKey || label) / simplified HKDF-style derivation
    private static byte[] hkdfLike(byte[] masterKey, String label) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(masterKey);
            sha.update(label.getBytes(StandardCharsets.UTF_8));
            return sha.digest();
        } catch (Exception e) {
            throw new IllegalStateException("Key derivation failed", e);
        }
    }
}
