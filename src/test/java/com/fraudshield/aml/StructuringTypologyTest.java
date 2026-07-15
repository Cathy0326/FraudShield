package com.fraudshield.aml;

import com.fraudshield.aml.impl.StructuringTypology;
import com.fraudshield.dto.AmlSignal;
import com.fraudshield.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StructuringTypologyTest {

    @Mock StringRedisTemplate redis;
    @Mock ZSetOperations<String, String> zset;

    private StructuringTypology typology;

    @BeforeEach
    void setUp() {
        when(redis.opsForZSet()).thenReturn(zset);
        typology = new StructuringTypology(redis);
    }

    private Transaction txn(double amount) {
        return Transaction.builder().transactionId("TXN-X").senderAccount("ACC-9")
                .senderName("Dana Rowe").receiverAccount("ACC-OFF").receiverName("Quiet Bay")
                .amount(amount).currency("USD").build();
    }

    @Test
    void threeSubThresholdTransfersOverTenK_fires() {
        // window already holds two prior sub-$10k transfers; this is the third
        lenient().when(zset.rangeByScore(anyString(), anyDouble(), anyDouble()))
                .thenReturn(Set.of("TXN-A|9200.0", "TXN-B|9300.0", "TXN-X|9400.0"));

        AmlSignal s = typology.evaluate(txn(9400.0));

        assertThat(s.getScore()).isGreaterThan(0.5);
        assertThat(s.getExplanation()).contains("structuring").contains("CTR");
    }

    @Test
    void singleLargeTransferAboveThreshold_isNotStructuring() {
        // a $15k transfer is a CTR matter, not structuring — it's out of the band, nothing accumulates
        lenient().when(zset.rangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(Set.of());

        AmlSignal s = typology.evaluate(txn(15_000.0));

        assertThat(s.getScore()).isEqualTo(0.0);
    }

    @Test
    void tooFewTransfers_doesNotFire() {
        lenient().when(zset.rangeByScore(anyString(), anyDouble(), anyDouble()))
                .thenReturn(Set.of("TXN-X|9400.0"));

        AmlSignal s = typology.evaluate(txn(9400.0));

        assertThat(s.getScore()).isEqualTo(0.0);
    }
}
