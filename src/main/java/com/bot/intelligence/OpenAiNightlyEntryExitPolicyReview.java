package com.bot.intelligence;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * After-hours OpenAI review of entry/exit performance.
 *
 * This class never runs source-code mutation during live trading. It summarizes
 * trade outcomes, dynamic governor decisions, and policy files, then asks OpenAI
 * for structured policy recommendations. Recommendations are written to disk for
 * the existing autonomous optimizer/test pipeline to consume.
 */
public final class OpenAiNightlyEntryExitPolicyReview {
    private final boolean enabled = envBool("OPENAI_NIGHTLY_ENTRY_EXIT_REVIEW_ENABLED", true);
    private final String model = env("OPENAI_NIGHTLY_ENTRY_EXIT_MODEL", OpenAiClientManager.getInstance().defaultModel());
    private final Path reportPath = Path.of(env("OPENAI_NIGHTLY_ENTRY_EXIT_REVIEW_PATH", "logs/openai_nightly_entry_exit_policy_review.json"));
    private final Path recommendedPolicyPath = Path.of(env("OPENAI_ENTRY_EXIT_POLICY_RECOMMENDATIONS_PATH", "logs/openai_entry_exit_policy_recommendations.properties"));
    private final OpenAiClientManager client = OpenAiClientManager.getInstance();

    public ReviewResult run() {
        long started = System.currentTimeMillis();
        if (!enabled) {
            write(reportPath, disabled("OPENAI_NIGHTLY_ENTRY_EXIT_REVIEW_ENABLED=false"));
            return new ReviewResult(false, false, "disabled", 0L);
        }
        if (!client.isUsable()) {
            write(reportPath, disabled("OPENAI_API_KEY missing or OpenAI agents disabled"));
            return new ReviewResult(false, false, "OpenAI unavailable", 0L);
        }

        String input = buildInput();
        int outputTokens = envInt("OPENAI_NIGHTLY_ENTRY_EXIT_MAX_OUTPUT_TOKENS", 900);
        int timeoutSeconds = envInt("OPENAI_NIGHTLY_REQUEST_TIMEOUT_SECONDS", 180);
        int retries = envInt("OPENAI_NIGHTLY_MAX_RETRIES", 1);
        OpenAiClientManager.OpenAiResult result = client.requestJsonWithTimeout(
                "NIGHTLY_ENTRY_EXIT_POLICY_REVIEW",
                model,
                instructions(),
                input,
                outputTokens,
                timeoutSeconds,
                retries
        );
        if (!result.ok && looksLikeTimeout(result.error)) {
            String compact = compactInput(input, envInt("OPENAI_NIGHTLY_TIMEOUT_RETRY_MAX_INPUT_CHARS", 9000));
            String retryModel = env("OPENAI_NIGHTLY_TIMEOUT_RETRY_MODEL", model);
            System.out.println("NIGHTLY OPENAI ENTRY/EXIT POLICY REVIEW RETRY: reason=timeout compactChars=" + compact.length()
                    + " model=" + retryModel + " timeoutSeconds=" + timeoutSeconds);
            result = client.requestJsonWithTimeout(
                    "NIGHTLY_ENTRY_EXIT_POLICY_REVIEW_RETRY_COMPACT",
                    retryModel,
                    instructions() + " Be concise. Focus on deployable entry/exit policy changes only.",
                    compact,
                    Math.max(450, outputTokens / 2),
                    timeoutSeconds,
                    0
            );
        }
        if (!result.ok) {
            String report = "{\n  \"timestamp\": " + OpenAiClientManager.json(Instant.now().toString())
                    + ",\n  \"ok\": false,\n  \"error\": " + OpenAiClientManager.json(result.error)
                    + ",\n  \"hint\": \"OpenAI reached but failed. Increase OPENAI_NIGHTLY_REQUEST_TIMEOUT_SECONDS or use OPENAI_NIGHTLY_TIMEOUT_RETRY_MODEL for a faster model. Local policy promotion continues safely.\"\n}";
            write(reportPath, report);
            return new ReviewResult(true, false, result.error, System.currentTimeMillis() - started);
        }
        write(reportPath, result.outputText == null || result.outputText.isBlank() ? result.rawBody : result.outputText);
        write(recommendedPolicyPath, toPolicyFile(result.outputText));
        return new ReviewResult(true, true, "reportPath=" + reportPath + " policyRecommendations=" + recommendedPolicyPath, System.currentTimeMillis() - started);
    }

    private String buildInput() {
        StringBuilder b = new StringBuilder();
        b.append("Analyze entry/exit performance and propose policy changes. Do not propose bypassing hard risk controls.\n");
        b.append("Use the reduced context. It is intentionally clustered/summarized to avoid expensive raw-log OpenAI calls.\n");
        if (envBool("OPENAI_NIGHTLY_REDUCE_CONTEXT_ENABLED", true)) {
            NightlyOpenAiContextReducer.Result reduced = new NightlyOpenAiContextReducer().reduce();
            System.out.println("NIGHTLY OPENAI CONTEXT REDUCER COMPLETE: " + reduced.summary());
            b.append("\n--- reduced_nightly_context path=").append(reduced.path).append(" ---\n");
            b.append(reduced.text).append('\n');
        } else {
            appendFile(b, "trade_outcomes", Path.of(env("TRADE_OUTCOMES_PATH", "logs/trade_outcomes.csv")), envInt("OPENAI_NIGHTLY_RAW_TRADE_OUTCOME_LINES", 80));
            appendFile(b, "dynamic_entry_exit_journal", Path.of(env("DYNAMIC_ENTRY_EXIT_AGENT_JOURNAL", "logs/dynamic_entry_exit_agent.csv")), envInt("OPENAI_NIGHTLY_RAW_DYNAMIC_LINES", 80));
            appendFile(b, "openai_entry_exit_governor", Path.of(env("OPENAI_ENTRY_EXIT_GOVERNOR_JOURNAL", "logs/openai_entry_exit_governor.csv")), envInt("OPENAI_NIGHTLY_RAW_GOVERNOR_LINES", 60));
            appendFile(b, "trade_lifecycle_recommendations", Path.of(env("TRADE_LIFECYCLE_RECOMMENDATIONS_PATH", "logs/trade_lifecycle_recommendations.properties")), 80);
            appendFile(b, "ai_policy", Path.of(env("AI_POLICY_PATH", "logs/ai_policy.properties")), 80);
        }
        b.append("\nReturn JSON keys: summary, observed_failure_modes, entry_policy_recommendations, exit_policy_recommendations, sizing_recommendations, cooldown_recommendations, tests_required, safe_env_overrides.\n");
        int maxChars = Math.max(5000, envInt("OPENAI_NIGHTLY_MAX_INPUT_CHARS", 18000));
        if (b.length() > maxChars) {
            return b.substring(0, maxChars) + "\n[TRUNCATED_OPENAI_NIGHTLY_INPUT maxChars=" + maxChars + "]\n";
        }
        return b.toString();
    }

    private static String instructions() {
        return "You are the after-hours policy reviewer for a self-improving AI scalping/momentum trading system. "
                + "The mission is volume-first scalping: find the most liquid, highest-volume, most violent movers; buy dip recoveries; short apex failures; exit quickly; and improve every night so tomorrow's scalps are better. "
                + "Prioritize blocked top-volume opportunities, missed violent moves, bad entry timing, bad exits, stale/low-liquidity churn, and filters that prevented valid scalps. "
                + "Recommend policy/config changes and tests, not live source edits. Return strict JSON only.";
    }

    private static void appendFile(StringBuilder b, String label, Path path, int maxLines) {
        b.append("\n--- ").append(label).append(" path=").append(path).append(" ---\n");
        try {
            if (!Files.exists(path)) {
                b.append("missing\n");
                return;
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - Math.max(1, maxLines));
            for (int i = start; i < lines.size(); i++) {
                b.append(lines.get(i)).append('\n');
            }
        } catch (Exception e) {
            b.append("read_failed: ").append(e.getMessage()).append('\n');
        }
    }

    private static String toPolicyFile(String json) {
        List<String> lines = new ArrayList<>();
        lines.add("# OpenAI after-hours entry/exit policy recommendations");
        lines.add("# Generated " + Instant.now());
        lines.add("# This file is advisory. Promotion should happen only through tests/backtests.");
        lines.add("openai.entry.exit.review.generatedAt=" + Instant.now());
        lines.add("openai.entry.exit.review.reportPath=logs/openai_nightly_entry_exit_policy_review.json");
        lines.add("openai.entry.exit.review.status=ready");
        lines.add("openai.entry.exit.review.rawJsonLength=" + (json == null ? 0 : json.length()));
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }


    private static boolean looksLikeTimeout(String error) {
        String e = error == null ? "" : error.toLowerCase(Locale.ROOT);
        return e.contains("timed out") || e.contains("timeout") || e.contains("timedout") || e.contains("http timeout");
    }

    private static String compactInput(String input, int maxChars) {
        if (input == null) return "";
        int safeMax = Math.max(3000, maxChars);
        if (input.length() <= safeMax) return input;
        String head = input.substring(0, Math.min(input.length(), safeMax / 2));
        String tail = input.substring(Math.max(0, input.length() - safeMax / 2));
        return head + "\n[COMPACT_TIMEOUT_RETRY: middle omitted to fit OpenAI timeout budget]\n" + tail;
    }

    private static String disabled(String reason) {
        return "{\n  \"timestamp\": " + OpenAiClientManager.json(Instant.now().toString()) + ",\n  \"enabled\": false,\n  \"reason\": " + OpenAiClientManager.json(reason) + "\n}";
    }

    private static void write(Path path, String text) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                w.write(text == null ? "" : text);
            }
        } catch (Exception e) {
            System.out.println("OPENAI NIGHTLY ENTRY/EXIT REVIEW WRITE FAILED: " + e.getMessage());
        }
    }

    public static final class ReviewResult {
        public final boolean attempted;
        public final boolean ok;
        public final String message;
        public final long elapsedMs;
        private ReviewResult(boolean attempted, boolean ok, String message, long elapsedMs) {
            this.attempted = attempted; this.ok = ok; this.message = message == null ? "" : message; this.elapsedMs = elapsedMs;
        }
        public String summary() { return "attempted=" + attempted + " ok=" + ok + " elapsedMs=" + elapsedMs + " " + message; }
    }

    private static String env(String key, String fallback) { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : v.trim(); }
    private static boolean envBool(String key, boolean fallback) { String v = System.getenv(key); if (v == null || v.isBlank()) return fallback; String x = v.trim().toLowerCase(Locale.ROOT); return x.equals("true") || x.equals("1") || x.equals("yes") || x.equals("on"); }
    private static int envInt(String key, int fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); } catch(Exception e) { return fallback; } }
}
