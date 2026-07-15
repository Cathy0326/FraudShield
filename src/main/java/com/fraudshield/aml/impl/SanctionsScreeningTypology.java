package com.fraudshield.aml.impl;

import com.fraudshield.aml.AmlTypology;
import com.fraudshield.aml.SanctionsWatchlist;
import com.fraudshield.dto.AmlSignal;
import com.fraudshield.model.Transaction;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 制裁/PEP名单筛查 —— 交易任一方命中名单即高分告警。
 * Sanctions / PEP screening: a hit on either party is a near-certain, must-report
 * signal. Unlike the statistical typologies this is close to binary — a confirmed
 * sanctions match is escalated regardless of the rest of the score.
 */
@Component
public class SanctionsScreeningTypology implements AmlTypology {

    private final SanctionsWatchlist watchlist;

    public SanctionsScreeningTypology(SanctionsWatchlist watchlist) {
        this.watchlist = watchlist;
    }

    @Override
    public AmlSignal evaluate(Transaction txn) {
        Optional<SanctionsWatchlist.Entry> sender = watchlist.screen(txn.getSenderName());
        Optional<SanctionsWatchlist.Entry> receiver = watchlist.screen(txn.getReceiverName());
        Optional<SanctionsWatchlist.Entry> hit = sender.or(() -> receiver);

        if (hit.isEmpty()) {
            return miss();
        }
        String party = sender.isPresent() ? "sender" : "receiver";
        String name = sender.isPresent() ? txn.getSenderName() : txn.getReceiverName();
        SanctionsWatchlist.Entry e = hit.get();
        // PEP略低于制裁：PEP本身不违法，但需强化尽调 / PEP slightly lower — not illegal, but EDD
        double score = "PEP".equals(e.type()) ? 0.75 : 0.98;
        return AmlSignal.builder()
                .typology(getName().replace("Typology", ""))
                .score(score)
                .explanation(String.format("%s \"%s\" matches %s list \"%s\" (%s)",
                        party, name, e.type(), e.name(), e.program()))
                .build();
    }

    private AmlSignal miss() {
        return AmlSignal.builder().typology(getName().replace("Typology", ""))
                .score(0.0).explanation("No watchlist match").build();
    }
}
