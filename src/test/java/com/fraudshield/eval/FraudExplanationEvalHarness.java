package com.fraudshield.eval;

import com.fraudshield.model.AiAnalysis;
import com.fraudshield.service.AzureOpenAIService;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

/**
 * Manual eval harness for {@link AzureOpenAIService#analyze}. Not wired into
 * Surefire/Failsafe — it makes real, billed Azure OpenAI calls, so it must
 * not run on every `mvn test`/`mvn verify` (and CI has no API key configured
 * anyway). Run it explicitly:
 *
 * <pre>
 *   export AZURE_OPENAI_ENDPOINT=https://&lt;resource&gt;.services.ai.azure.com/openai/v1
 *   export AZURE_OPENAI_KEY=...
 *   export AZURE_OPENAI_DEPLOYMENT=gpt-4.1-mini   # optional, defaults below
 *   mvn test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -q
 *   java -cp "target/test-classes:target/classes:$(cat target/cp.txt)" \
 *       com.fraudshield.eval.FraudExplanationEvalHarness
 * </pre>
 *
 * It bypasses Spring entirely (no application context needed) by setting
 * {@code AzureOpenAIService}'s {@code @Value}-injected fields directly via
 * reflection — those fields only exist to be populated by Spring at runtime,
 * not because the class is otherwise coupled to it.
 *
 * <p>What this catches that unit tests can't: unit tests mock the HTTP call
 * and verify parsing logic; this exercises the real model and asks "is the
 * recommendation actually reasonable," which is the part that silently
 * drifts when the prompt, model version, or deployment changes.
 */
public final class FraudExplanationEvalHarness {

    private FraudExplanationEvalHarness() {
    }

    public static void main(String[] args) throws Exception {
        AzureOpenAIService service = new AzureOpenAIService();
        configure(service);

        List<EvalCase> cases = EvalCases.all();
        int matched = 0;
        int aiEnhancedCount = 0;
        long totalMillis = 0;

        System.out.println("=== Fraud-explanation LLM eval — " + cases.size() + " cases ===\n");

        for (EvalCase evalCase : cases) {
            long start = System.currentTimeMillis();
            AiAnalysis result = service.analyze(evalCase.order(), evalCase.riskResult());
            long elapsed = System.currentTimeMillis() - start;
            totalMillis += elapsed;

            boolean matches = evalCase.expectedRecommendation().equalsIgnoreCase(result.getRecommendation());
            if (matches) {
                matched++;
            }
            if (result.isAiEnhanced()) {
                aiEnhancedCount++;
            }

            System.out.printf(Locale.ROOT,
                    "[%s] expected=%-15s actual=%-15s confidence=%.2f aiEnhanced=%-5s latency=%dms%s%n",
                    evalCase.name(), evalCase.expectedRecommendation(), result.getRecommendation(),
                    result.getConfidence() == null ? 0.0 : result.getConfidence(),
                    result.isAiEnhanced(), elapsed,
                    matches ? "" : "  <-- MISMATCH: " + result.getReasoning());
        }

        int total = cases.size();
        System.out.println();
        System.out.printf(Locale.ROOT, "Recommendation agreement: %d/%d (%.0f%%)%n",
                matched, total, 100.0 * matched / total);
        System.out.printf(Locale.ROOT, "AI-enhanced responses:    %d/%d (%.0f%%)%n",
                aiEnhancedCount, total, 100.0 * aiEnhancedCount / total);
        System.out.printf(Locale.ROOT, "Average latency:          %dms%n", totalMillis / total);

        if (matched < total) {
            System.out.println("\nSome cases disagreed with the expected recommendation — "
                    + "review whether the prompt/model needs adjustment or the expectation was wrong.");
        }
    }

    private static void configure(AzureOpenAIService service) throws Exception {
        setField(service, "endpoint", envOrFail("AZURE_OPENAI_ENDPOINT"));
        setField(service, "apiKey", envOrFail("AZURE_OPENAI_KEY"));
        setField(service, "deployment", System.getenv().getOrDefault("AZURE_OPENAI_DEPLOYMENT", "gpt-4.1-mini"));
        setField(service, "aiEnabled", true);
    }

    private static String envOrFail(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set to run this harness — "
                    + "it makes real Azure OpenAI calls and has no fallback credential.");
        }
        return value;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = AzureOpenAIService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
