package com.bot.intelligence;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cost-control reducer for nightly OpenAI calls.
 *
 * The replay and research pipeline can generate hundreds of thousands or millions
 * of rows. Sending raw rows to OpenAI is expensive, slow, and often lower quality
 * than sending clustered failure modes. This reducer converts the nightly data
 * lake into a compact decision memo so OpenAI reviews only high-information
 * patterns.
 */
public final class NightlyOpenAiContextReducer {
    private final Path tradeOutcomesPath = Path.of(env("TRADE_OUTCOMES_PATH", "logs/trade_outcomes.csv"));
    private final Path dynamicJournalPath = Path.of(env("DYNAMIC_ENTRY_EXIT_AGENT_JOURNAL", "logs/dynamic_entry_exit_agent.csv"));
    private final Path openAiGovernorPath = Path.of(env("OPENAI_ENTRY_EXIT_GOVERNOR_JOURNAL", "logs/openai_entry_exit_governor.csv"));
    private final Path lifecycleRecommendationsPath = Path.of(env("TRADE_LIFECYCLE_RECOMMENDATIONS_PATH", "logs/trade_lifecycle_recommendations.properties"));
    private final Path activePolicyPath = Path.of(env("ENTRY_EXIT_ACTIVE_POLICY_PATH", "logs/entry_exit_policy_active.properties"));
    private final Path aiPolicyPath = Path.of(env("AI_POLICY_PATH", "logs/ai_policy.properties"));
    private final Path outputPath = Path.of(env("OPENAI_NIGHTLY_REDUCED_CONTEXT_PATH", "logs/openai_nightly_reduced_context.txt"));

    private final int maxExamplesPerBucket = Math.max(1, envInt("OPENAI_NIGHTLY_EXAMPLES_PER_BUCKET", 4));
    private final int maxChars = Math.max(5000, envInt("OPENAI_NIGHTLY_MAX_INPUT_CHARS", 12000));

    public Result reduce() {
        long started = System.currentTimeMillis();
        StringBuilder out = new StringBuilder();
        out.append("OPENAI NIGHTLY ENTRY/EXIT REDUCED CONTEXT\n");
        out.append("generatedAt=").append(Instant.now()).append('\n');
        out.append("purpose=Reduce millions of replay/log rows into a compact high-information memo for policy evolution.\n");
        out.append("maxChars=").append(maxChars).append(" examplesPerBucket=").append(maxExamplesPerBucket).append("\n\n");

        FileSummary outcomes = summarizeFile("trade_outcomes", tradeOutcomesPath);
        FileSummary dynamic = summarizeFile("dynamic_entry_exit_journal", dynamicJournalPath);
        FileSummary governor = summarizeFile("openai_entry_exit_governor", openAiGovernorPath);

        appendSummary(out, outcomes);
        appendSummary(out, dynamic);
        appendSummary(out, governor);

        out.append("\n--- clustered_failure_modes ---\n");
        ClusterBook clusters = new ClusterBook(maxExamplesPerBucket);
        clusters.scan(outcomes.lines);
        clusters.scan(dynamic.lines);
        clusters.scan(governor.lines);
        out.append(clusters.render());

        out.append("\n--- current_policy_snapshot ---\n");
        appendProperties(out, "entry_exit_active_policy", activePolicyPath, 80);
        appendProperties(out, "ai_policy", aiPolicyPath, 80);
        appendProperties(out, "trade_lifecycle_recommendations", lifecycleRecommendationsPath, 80);

        out.append("\n--- reviewer_instructions_context ---\n");
        out.append("Focus on repeated failure modes, overtrading, low-quality entries, tiny entries, premature exits, no-trade blockers, OpenAI quota failures, and market-data repair failures.\n");
        out.append("Do not recommend removing hard safety limits. Prefer policy thresholds, sizing, cooldowns, market-data repair, and validation-gated promotion.\n");

        String text = out.toString();
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars)
                    + "\n\n[TRUNCATED_BY_NIGHTLY_OPENAI_CONTEXT_REDUCER originalChars=" + out.length() + " maxChars=" + maxChars + "]\n";
        }
        write(outputPath, text);
        return new Result(true, outputPath, text, System.currentTimeMillis() - started,
                outcomes.lineCount + dynamic.lineCount + governor.lineCount, text.length());
    }

    private static void appendSummary(StringBuilder out, FileSummary s) {
        out.append("\n--- ").append(s.label).append(" summary path=").append(s.path).append(" ---\n");
        out.append("exists=").append(s.exists)
                .append(" lines=").append(s.lineCount)
                .append(" chars=").append(s.charCount)
                .append(" keywords=").append(s.keywordCounts)
                .append('\n');
        if (!s.lastLines.isEmpty()) {
            out.append("recent_examples:\n");
            for (String line : s.lastLines) {
                out.append("  ").append(abbrev(line, 500)).append('\n');
            }
        }
    }

    private FileSummary summarizeFile(String label, Path path) {
        FileSummary s = new FileSummary(label, path);
        try {
            if (!Files.exists(path)) {
                s.exists = false;
                return s;
            }
            s.exists = true;
            List<String> all = Files.readAllLines(path, StandardCharsets.UTF_8);
            s.lines = all;
            s.lineCount = all.size();
            long chars = 0;
            for (String line : all) chars += line == null ? 0 : line.length();
            s.charCount = chars;
            int start = Math.max(0, all.size() - maxExamplesPerBucket);
            for (int i = start; i < all.size(); i++) {
                String line = all.get(i);
                if (line != null && !line.isBlank()) s.lastLines.add(line);
            }
            for (String line : all) {
                String u = line == null ? "" : line.toUpperCase(Locale.ROOT);
                countIf(s.keywordCounts, "BUY", u.contains("BUY"));
                countIf(s.keywordCounts, "SELL_EXIT", u.contains("SELL") || u.contains("EXIT"));
                countIf(s.keywordCounts, "VETO_REJECT", u.contains("VETO") || u.contains("REJECT"));
                countIf(s.keywordCounts, "NO_MARKET_DATA", u.contains("NO_VALID_MARKET_DATA") || u.contains("STALE_BAR"));
                countIf(s.keywordCounts, "TINY_OR_MIN_NOTIONAL", u.contains("TINY") || u.contains("MIN_NOTIONAL") || u.contains("TOO_SMALL"));
                countIf(s.keywordCounts, "COOLDOWN", u.contains("COOLDOWN"));
                countIf(s.keywordCounts, "LOSS", u.contains("LOSS") || u.contains("NEGATIVE") || u.contains("DRAWDOWN"));
                countIf(s.keywordCounts, "OPENAI_QUOTA", u.contains("INSUFFICIENT_QUOTA") || u.contains("HTTP 429"));
            }
        } catch (Exception e) {
            s.exists = Files.exists(path);
            s.lastLines.add("read_failed=" + e.getMessage());
        }
        return s;
    }

    private static void countIf(Map<String, Integer> m, String k, boolean condition) {
        if (!condition) return;
        m.put(k, m.getOrDefault(k, 0) + 1);
    }

    private static void appendProperties(StringBuilder out, String label, Path path, int maxLines) {
        out.append("\n[").append(label).append(" path=").append(path).append("]\n");
        try {
            if (!Files.exists(path)) {
                out.append("missing\n");
                return;
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - Math.max(1, maxLines));
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line == null) continue;
                String trimmed = line.trim();
                if (trimmed.isBlank()) continue;
                out.append(trimmed).append('\n');
            }
        } catch (Exception e) {
            out.append("read_failed=").append(e.getMessage()).append('\n');
        }
    }

    private static void write(Path path, String text) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                w.write(text == null ? "" : text);
            }
        } catch (Exception e) {
            System.out.println("NIGHTLY OPENAI CONTEXT REDUCER WRITE FAILED: " + e.getMessage());
        }
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    private static String env(String key, String fallback) { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : v.trim(); }
    private static int envInt(String key, int fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); } catch(Exception e) { return fallback; } }

    private static final class FileSummary {
        final String label;
        final Path path;
        boolean exists;
        int lineCount;
        long charCount;
        List<String> lines = List.of();
        final List<String> lastLines = new ArrayList<>();
        final Map<String, Integer> keywordCounts = new LinkedHashMap<>();
        FileSummary(String label, Path path) { this.label = label; this.path = path; }
    }

    private static final class ClusterBook {
        private final int examplesPerBucket;
        private final Map<String, Bucket> buckets = new LinkedHashMap<>();
        ClusterBook(int examplesPerBucket) { this.examplesPerBucket = examplesPerBucket; }

        void scan(List<String> lines) {
            if (lines == null) return;
            for (String line : lines) {
                String key = classify(line);
                Bucket b = buckets.computeIfAbsent(key, Bucket::new);
                b.count++;
                if (b.examples.size() < examplesPerBucket && line != null && !line.isBlank()) {
                    b.examples.add(abbrev(line, 500));
                }
            }
        }

        String render() {
            StringBuilder b = new StringBuilder();
            buckets.values().stream()
                    .sorted((a, c) -> Integer.compare(c.count, a.count))
                    .limit(18)
                    .forEach(bucket -> {
                        b.append("bucket=").append(bucket.key).append(" count=").append(bucket.count).append('\n');
                        for (String ex : bucket.examples) b.append("  example=").append(ex).append('\n');
                    });
            return b.toString();
        }

        private static final class Bucket {
            final String key;
            int count;
            final List<String> examples = new ArrayList<>();
            Bucket(String key) { this.key = key; }
        }

        private static String classify(String line) {
            String u = line == null ? "" : line.toUpperCase(Locale.ROOT);
            if (u.contains("INSUFFICIENT_QUOTA") || u.contains("HTTP 429")) return "OPENAI_QUOTA_OR_RATE_LIMIT";
            if (u.contains("NO_VALID_MARKET_DATA") || u.contains("STALE_BAR")) return "MARKET_DATA_BLOCKER";
            if (u.contains("MIN_NOTIONAL") || u.contains("TOO_SMALL") || u.contains("TINY")) return "TINY_TRADE_OR_MIN_NOTIONAL";
            if (u.contains("COOLDOWN")) return "COOLDOWN_OR_CHURN_CONTROL";
            if (u.contains("VETO") || u.contains("REJECT")) return "ENTRY_VETO_OR_REJECTION";
            if (u.contains("EXIT") || u.contains("SELL")) return "EXIT_DECISION";
            if (u.contains("BUY") || u.contains("ENTRY")) return "ENTRY_DECISION";
            if (u.contains("LOSS") || u.contains("DRAWDOWN")) return "LOSS_OR_DRAWDOWN";
            return "OTHER";
        }
    }

    public static final class Result {
        public final boolean ok;
        public final Path path;
        public final String text;
        public final long elapsedMs;
        public final int sourceRows;
        public final int reducedChars;
        Result(boolean ok, Path path, String text, long elapsedMs, int sourceRows, int reducedChars) {
            this.ok = ok; this.path = path; this.text = text == null ? "" : text; this.elapsedMs = elapsedMs;
            this.sourceRows = sourceRows; this.reducedChars = reducedChars;
        }
        public String summary() {
            return "ok=" + ok + " sourceRows=" + sourceRows + " reducedChars=" + reducedChars + " elapsedMs=" + elapsedMs + " path=" + path;
        }
    }
}
