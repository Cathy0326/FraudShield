package com.fraudshield.aml;

import com.fraudshield.aml.impl.SanctionsScreeningTypology;
import com.fraudshield.dto.AmlSignal;
import com.fraudshield.model.Transaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SanctionsScreeningTypologyTest {

    private final SanctionsScreeningTypology typology =
            new SanctionsScreeningTypology(new SanctionsWatchlist());

    private Transaction txn(String senderName, String receiverName) {
        return Transaction.builder()
                .transactionId("TXN-1").senderAccount("A").senderName(senderName)
                .receiverAccount("B").receiverName(receiverName).amount(500.0).build();
    }

    @Test
    void exactMatch_onReceiver_scoresHigh() {
        AmlSignal s = typology.evaluate(txn("Jane Miller", "Viktor Petrov"));
        assertThat(s.getScore()).isGreaterThanOrEqualTo(0.95);
        assertThat(s.getExplanation()).contains("receiver").contains("SANCTIONS");
    }

    @Test
    void fuzzyMatch_toleratesOneCharDrift() {
        // "Viktor Petrsv" is edit-distance 1 from "Viktor Petrov"
        AmlSignal s = typology.evaluate(txn("Viktor Petrsv", "City Utilities"));
        assertThat(s.getScore()).isGreaterThan(0.0);
    }

    @Test
    void pepMatch_scoresLowerThanSanctions() {
        AmlSignal s = typology.evaluate(txn("Elena Vasquez", "Someone"));
        assertThat(s.getScore()).isEqualTo(0.75);
    }

    @Test
    void cleanParties_noSignal() {
        AmlSignal s = typology.evaluate(txn("Jane Miller", "City Utilities"));
        assertThat(s.getScore()).isEqualTo(0.0);
    }
}
