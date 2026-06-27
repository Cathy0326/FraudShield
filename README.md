# FraudShield
A Real-time E-commerce Order Anomaly Detection and Risk Control System uses streaming data and Machine Learning (ML) to intercept fraudulent transactions, inventory shortages, and pricing anomalies instantly. It actively assesses risks in sub-seconds before orders are finalized.

## AI fraud-explanation eval harness

`AzureOpenAIService` gives medium/high-risk orders an LLM second opinion.
`src/test/java/com/fraudshield/eval/FraudExplanationEvalHarness.java` is a
manual harness that runs a fixed set of synthetic order/risk fixtures
through the real model and checks whether its recommendation
(`block`/`manual_review`/`monitor`/`allow`) matches what a reviewer would
expect, plus latency and the AI-enhanced (vs. fallback) rate. It makes real,
billed API calls, so it's not part of `mvn test`/CI — run it explicitly:

```bash
export AZURE_OPENAI_ENDPOINT=https://<resource>.services.ai.azure.com/openai/v1
export AZURE_OPENAI_KEY=...
mvn test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -q
java -cp "target/test-classes:target/classes:$(cat target/cp.txt)" \
    com.fraudshield.eval.FraudExplanationEvalHarness
```
See [SECURITY.md](SECURITY.md) for security incidents found and fixed in this project, with root-cause analysis.
