# FraudShield

Real-time e-commerce fraud detection with a human-in-the-loop review workflow. Orders
stream through Kafka into a rule engine that scores every order in under a second;
uncertain cases get an LLM second opinion; flagged orders land in a review queue where
human decisions become labeled data that feeds back into detection itself.

```
                     ┌──────────────────────────── learn ─────────────────────────────┐
                     ▼                                                                 │
Orders ──▶ Kafka ──▶ Rule Engine ──▶ AI second opinion ──▶ Review Queue ──▶ Human decision
           (stream)  (5 rules,       (Azure OpenAI,        (ops workbench)  (labels)
                      sub-second)     MEDIUM only)
```

The feedback loop is the point: a reviewer clicking **Confirm Fraud** simultaneously
(1) updates per-rule precision stats, (2) arms `ConfirmedFraudHistoryRule` against that
user and their IPs, (3) seeds graph risk propagation across the whole fraud ring, and
(4) tunes the weight each rule carries in the scoring engine.

## Detection: three signal dimensions

| Dimension | What it catches | How |
|---|---|---|
| **Volume** | Clumsy fraud — too much, too fast | `FrequentIpRule` (velocity), `AbnormalAmountRule` (EMA deviation), `HighAmountNewUserRule` |
| **Identity** | Repeat offenders | `BlacklistRule` (curated Redis sets), `ConfirmedFraudHistoryRule` (auto-generated from review labels — no manual sync) |
| **Pattern** | Card testing ("John Doe" attacks) | `CardTestingRule` — micro-charges (≤$10) × many distinct identities × one IP/device in a 10-min Redis sliding window. Velocity rules miss this: the attacker slows down but can't hide the small-amount + multi-identity combination |
| **Relationship** | Organized fraud rings | Shared-IP/shared-device linked accounts (2-hop), graph risk propagation (multi-hop, power-iteration on the user–IP–device graph with Euclidean-norm convergence) |

The multi-hop case is the interesting one: in a chain `A—IP1—B—IP2—C`, user C shares
nothing directly with confirmed fraudster A, yet propagation gives C a non-zero,
distance-decayed "Network Risk" score. Fraud rings rotate accounts but reuse
infrastructure — labels earned on old accounts catch the new ones. Linkage runs
over both IPs and device fingerprints: shared NAT makes IP matches noisy, while
the same device across "different" accounts rarely lies.

**Combining signals**: rule outputs are merged with precision-weighted noisy-OR —
`combined = 1 − Π(1 − wᵢ·sᵢ)` — so corroborating evidence compounds (two independent
0.6 signals score 0.84, not 0.6), and each rule's weight comes from its *measured*
precision against review labels: rules that generate false alarms speak more quietly,
automatically.

## Human review workflow

Detection is only half a fraud system; the business process is
**detect → review → decide → learn**:

- **Review Queue** page: every flagged order awaiting a decision, with the total
  **$ at risk** front and center.
- Reviewers resolve each order to `CONFIRMED_FRAUD` / `FALSE_POSITIVE` / `APPROVED`.
  Terminal states are immutable (re-review → 409) so label data can't be corrupted.
  Reviewer identity comes from the JWT principal, never the request body.
- **Rule Precision** report: which rules fire correctly and which generate the most
  false alarms — measured from labels, not guessed. Unmeasured rules show
  "no labels yet", never a misleading 0%.
- **Tamper-evident audit chain**: every decision is a link in an HMAC-SHA256 hash
  chain (each record's hash covers the previous one). A DBA editing or deleting a
  historical decision breaks every subsequent link; `GET /api/audit/verify` recomputes
  the whole chain and pinpoints the first broken record. HMAC — not plain SHA-256 —
  because a keyless hash chain can simply be recomputed after tampering.
- **Dispute evidence export**: one click on an order assembles order facts, triggered
  rules with explanations, the AI analysis, the human decision, and the order's audit-chain
  records (with a whole-chain integrity attestation) into a print-ready document
  (Print → Save as PDF) plus a machine-readable JSON — exactly what a merchant submits
  when fighting a chargeback. This is the audit chain's second use case: proving to the
  card network that the decision record is unaltered.

## AI second opinion — with an eval harness

`AzureOpenAIService` gives MEDIUM-risk orders an LLM review (risk level, confidence,
recommended action). Two engineering rules applied throughout:

1. **AI failure never breaks the pipeline** — every exception falls back to a
   conservative `manual_review` recommendation, never a 500.
2. **"It works" is not a claim you can make about an LLM without measurement** —
   a manual eval harness runs fixed fixtures (including deliberately conflicting
   signals) against the real model and reports recommendation-agreement rate,
   latency, and fallback rate. It makes billed API calls, so it's excluded from CI:

```bash
export AZURE_OPENAI_ENDPOINT=https://<resource>.services.ai.azure.com/openai/v1
export AZURE_OPENAI_KEY=...
mvn test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -q
java -cp "target/test-classes:target/classes:$(cat target/cp.txt)" \
    com.fraudshield.eval.FraudExplanationEvalHarness
```

## Security

- **PII encrypted at rest**: `userId`, `ipAddress`, and `deviceId` are stored with deterministic
  AES-256-GCM (synthetic IV from HMAC) via a JPA converter — equality queries keep
  working, services stay unchanged, and a DB copy or backup no longer exposes personal
  data. Key via `FRAUDSHIELD_ENCRYPTION_KEY` (`openssl rand -base64 32`).
- **Idempotent Kafka consumption**: a DB unique constraint backstops the
  check-then-insert race, and a Redis `SETNX` marker stops at-least-once redelivery
  from double-counting.
- See [SECURITY.md](SECURITY.md) for incidents found and fixed in this project with
  root-cause analysis — including a privilege-escalation bug rooted in a subtle
  `permitAll()` misconception.

## Observability

Two dashboards, one rule: **every metric has exactly one owner** — the anti-pattern
isn't having two dashboards, it's the same number computed in two places that can
disagree.

- **React dashboard** (`localhost:3000`) — owns the *business snapshot*: risk
  distribution, rule hits, review queue, per-order drill-down. Source of truth:
  the application DB/Redis.
- **Grafana** (`localhost:3001`, via Prometheus at `localhost:9090`) — owns
  everything that only makes sense *over time*, in three rows:
  1. **Decision quality** (the headline row — this is what the project is for):
     review-queue depth, **$ at risk** pending review, average time-to-decision
     (the review SLA), decisions by outcome, and AI-enhanced vs fallback counts —
     a rising fallback line is an LLM integration problem invisible in the app UI.
  2. **Streaming pipeline**: risk-event throughput by level, rule-evaluation p95,
     Kafka consumer lag — sub-second scoring doesn't help if the message waited
     in the topic.
  3. **System**: request rate, p95 latency, JVM heap.

Prometheus's own scrape history provides the time dimension — no custom time-series
storage anywhere.

## Quick start

```bash
docker compose up -d          # zookeeper, kafka, redis, backend, frontend, prometheus, grafana
open http://localhost:3000    # login: admin / Admin@123
```

A built-in simulator emits one synthetic order every 4 seconds (~70% normal, the
rest tripping each detection rule), so every dashboard moves on its own — set
`SIMULATOR_ENABLED=false` to silence it. Then explore: Review Queue → decide an
order → Reports (rule precision reflects your decision) → any order detail (user risk
profile, linked accounts, network risk score). **Send Test Orders** still works for
an on-demand burst.

```bash
mvn test                      # full unit suite
mvn checkstyle:check spotbugs:check
```

## Tech stack

Java 17 · Spring Boot 3 (Web, Security/JWT, Data JPA, Kafka, Actuator) · Redis ·
Kafka · H2 (dev) · Micrometer + Prometheus + Grafana · Azure OpenAI ·
React 18 + Vite + Tailwind + Recharts · Docker Compose · Azure Container Apps
