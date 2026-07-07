package com.fraudshield.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldEncryptorTest {

    // 测试专用密钥（32字节全零的Base64）— 绝不能出现在生产配置里
    // Test-only key (base64 of 32 zero bytes) — must never appear in real config
    private static final String TEST_KEY =
            Base64.getEncoder().encodeToString(new byte[32]);

    private final FieldEncryptor encryptor = new FieldEncryptor(TEST_KEY);

    @Test
    void roundTrip_recoversPlaintext() {
        String ct = encryptor.encrypt("USER-42");

        assertThat(ct).startsWith("enc:v1:").isNotEqualTo("USER-42");
        assertThat(encryptor.decrypt(ct)).isEqualTo("USER-42");
    }

    @Test
    void deterministic_samePlaintextSameCiphertext() {
        // 等值查询依赖这一性质 / equality queries depend on this property
        assertThat(encryptor.encrypt("192.168.1.1"))
                .isEqualTo(encryptor.encrypt("192.168.1.1"));
    }

    @Test
    void differentPlaintexts_differentCiphertexts() {
        assertThat(encryptor.encrypt("USER-1")).isNotEqualTo(encryptor.encrypt("USER-2"));
    }

    @Test
    void legacyPlaintextRow_isReturnedAsIs() {
        // 加密启用前写入的行没有enc:v1:前缀 / rows written before encryption have no prefix
        assertThat(encryptor.decrypt("plain-old-user-id")).isEqualTo("plain-old-user-id");
    }

    @Test
    void nullValues_passThroughBothWays() {
        assertThat(encryptor.encrypt(null)).isNull();
        assertThat(encryptor.decrypt(null)).isNull();
    }

    @Test
    void noKeyConfigured_passesThroughWithoutEncrypting() {
        FieldEncryptor disabled = new FieldEncryptor("");

        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.encrypt("USER-42")).isEqualTo("USER-42");
    }

    @Test
    void noKeyConfigured_butEncryptedValueFound_throws() {
        // 有密文却没密钥 → 必须显式失败，绝不能把密文当明文返回给业务层
        // Ciphertext but no key must fail loudly - never hand ciphertext to the app as data
        FieldEncryptor disabled = new FieldEncryptor("");
        String ct = encryptor.encrypt("USER-42");

        assertThatThrownBy(() -> disabled.decrypt(ct))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no encryption key");
    }

    @Test
    void wrongKey_failsToDecrypt() {
        byte[] other = new byte[32];
        other[0] = 1;
        FieldEncryptor wrongKey = new FieldEncryptor(Base64.getEncoder().encodeToString(other));
        String ct = encryptor.encrypt("USER-42");

        // GCM认证标签校验失败 → 异常，而不是返回乱码
        // GCM's auth tag fails verification - an exception, never silent garbage
        assertThatThrownBy(() -> wrongKey.decrypt(ct))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidKeyLength_failsFastAtStartup() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new FieldEncryptor(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
