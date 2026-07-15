package com.fraudshield.aml;

import com.fraudshield.dto.AmlSignal;
import com.fraudshield.model.Transaction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AmlDetectionEngineTest {

    private final Transaction txn = Transaction.builder()
            .transactionId("TXN-1").senderAccount("A").receiverAccount("B").amount(9400.0).build();

    private AmlTypology fixed(String typology, double score) {
        return t -> AmlSignal.builder().typology(typology).score(score)
                .explanation(typology + " fired").build();
    }

    @Test
    void noTypologies_isClear() {
        var engine = new AmlDetectionEngine(List.of(fixed("Structuring", 0.0)));
        var alert = engine.screen(txn);
        assertThat(alert.getAlertLevel()).isEqualTo("CLEAR");
        assertThat(alert.getScore()).isEqualTo(0.0);
    }

    @Test
    void sanctionsHit_forcesFileSar_regardlessOfScore() {
        // a lone 0.75 PEP-style sanctions signal would be ESCALATE by score, but any
        // sanctions match must be reported
        var engine = new AmlDetectionEngine(List.of(fixed("SanctionsScreening", 0.75)));
        var alert = engine.screen(txn);
        assertThat(alert.getAlertLevel()).isEqualTo("FILE_SAR");
    }

    @Test
    void corroboratingTypologies_compoundViaNoisyOr() {
        // 1 - (1-0.6)(1-0.6) = 0.84 -> ESCALATE
        var engine = new AmlDetectionEngine(List.of(fixed("Structuring", 0.6), fixed("RapidMovement", 0.6)));
        var alert = engine.screen(txn);
        assertThat(alert.getScore()).isEqualTo(0.84);
        assertThat(alert.getAlertLevel()).isEqualTo("ESCALATE");
        assertThat(alert.getTypologies()).hasSize(2);
    }
}
