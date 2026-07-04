package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Active retest queue for strategies that are promising but not promotion-safe yet.
 *
 * The queue runs an isolated candidate-only bar replay, then combines that result
 * with paper and shadow evidence. It never directly promotes live sizing.
 */
public final class CandidateWatchlistRetestQueue {
    private final Path watchlistPath = Path.of(env("BAR_BY_BAR_CANDIDATE_WATCHLIST_PATH", "logs/bar_by_bar_candidate_watchlist.csv"));
    private final Path baseBarPolicyPath = Path.of(env("BAR_BY_BAR_SIMULATION_POLICY_PATH", "logs/bar_by_bar_simulation_policy.properties"));
    private final Path paperPolicyPath = Path.of(env("PAPER_TRADING_STRATEGY_POLICY_PATH", "logs/paper_trading_strategy_policy.properties"));
    private final Path shadowOutcomePath = Path.of(env("SHADOW_TRADE_OUTCOME_PATH", "logs/shadow_trade_outcomes.csv"));
    private final Path queuePath = Path.of(env("CANDIDATE_RETEST_QUEUE_PATH", "logs/candidate_retest_queue.csv"));
    private final Path reportPath = Path.of(env("CANDIDATE_RETEST_QUEUE_REPORT_PATH", "logs/candidate_retest_queue_report.txt"));
    private final Path policyPath = Path.of(env("CANDIDATE_RETEST_QUEUE_POLICY_PATH", "logs/candidate_retest_queue_policy.properties"));
    private final Path healthPath = Path.of(env("CANDIDATE_RETEST_QUEUE_HEALTH_PATH", "logs/candidate_retest_queue_health.properties"));
    private final Path statePath = Path.of(env("CANDIDATE_RETEST_QUEUE_STATE_PATH", "logs/candidate_retest_queue_state.properties"));
    private final Path retestBarPolicyPath = Path.of(env("BAR_BY_BAR_CANDIDATE_RETEST_POLICY_PATH", "logs/bar_by_bar_candidate_retest_policy.properties"));
    private final Path retestBarReportPath = Path.of(env("BAR_BY_BAR_CANDIDATE_RETEST_REPORT_PATH", "logs/bar_by_bar_candidate_retest_report.txt"));
    private final Path retestBarTradesPath = Path.of(env("BAR_BY_BAR_CANDIDATE_RETEST_TRADES_PATH", "logs/bar_by_bar_candidate_retest_trades.csv"));
    private final Path retestBarWatchlistPath = Path.of(env("BAR_BY_BAR_CANDIDATE_RETEST_WATCHLIST_PATH", "logs/bar_by_bar_candidate_retest_watchlist.csv"));

    private final boolean targetedReplayEnabled = envBool("CANDIDATE_RETEST_TARGETED_REPLAY_ENABLED", true);
    private final int maxAttempts = Math.max(1, envInt("CANDIDATE_RETEST_MAX_ATTEMPTS", 3));
    private final int minShadowSamples = Math.max(0, envInt("CANDIDATE_RETEST_MIN_SHADOW_SAMPLES", 5));
    private final double minShadowExpectancyDollars = envDouble("CANDIDATE_RETEST_MIN_SHADOW_EXPECTANCY_DOLLARS", 0.0);
    private final double minShadowProfitFactor = Math.max(0.0, envDouble("CANDIDATE_RETEST_MIN_SHADOW_PROFIT_FACTOR", 1.0));

    public Result run() {
        long startedMs = System.currentTimeMillis();
        List<WatchlistCandidate> candidates = readWatchlist();
        Properties state = loadProperties(statePath);
        Properties baseBarPolicy = loadProperties(baseBarPolicyPath);
        Properties paperPolicy = loadProperties(paperPolicyPath);
        Map<String, ShadowStats> shadowStats = readShadowOutcomes();

        PolygonBarByBarSimulationEngine.Result targetedReplay = null;
        Properties retestBarPolicy = new Properties();
        boolean replayRan = false;
        if (targetedReplayEnabled && !candidates.isEmpty()) {
            targetedReplay = runTargetedReplay(candidates);
            replayRan = targetedReplay != null;
            retestBarPolicy = loadProperties(retestBarPolicyPath);
        }
        if (retestBarPolicy.isEmpty()) {
            retestBarPolicy = baseBarPolicy;
        }

        List<RetestDecision> decisions = new ArrayList<>();
        for (WatchlistCandidate candidate : candidates) {
            int attempts = parseInt(state.getProperty(statePrefix(candidate.strategy) + "attempts"), 0) + 1;
            String firstSeen = firstNonBlank(
                    state.getProperty(statePrefix(candidate.strategy) + "firstSeen"),
                    candidate.updatedAt,
                    Instant.now().toString());
            Evidence evidence = Evidence.from(candidate, retestBarPolicy, paperPolicy,
                    shadowStats.getOrDefault(candidate.strategy, ShadowStats.empty(candidate.strategy)));
            RetestDecision decision = decide(candidate, evidence, attempts);
            decisions.add(decision);
            updateState(state, decision, firstSeen);
        }
        decisions.sort(Comparator
                .comparing((RetestDecision d) -> d.rank()).reversed()
                .thenComparing(d -> d.candidate.strategy));

        writeQueue(decisions);
        writeReport(decisions, targetedReplay, replayRan, startedMs, System.currentTimeMillis());
        writePolicy(decisions, replayRan);
        writeHealth(decisions, replayRan);
        saveProperties(statePath, state, "Candidate watchlist retest state.");

        int promotionReady = 0;
        int keepWatching = 0;
        int rejected = 0;
        for (RetestDecision decision : decisions) {
            if ("PROMOTION_READY".equals(decision.decision)) {
                promotionReady++;
            } else if ("REJECT".equals(decision.decision)) {
                rejected++;
            } else {
                keepWatching++;
            }
        }
        return new Result(candidates.size(), replayRan, promotionReady, keepWatching, rejected,
                queuePath, reportPath, policyPath, healthPath, System.currentTimeMillis() - startedMs);
    }

    private RetestDecision decide(WatchlistCandidate candidate, Evidence evidence, int attempts) {
        boolean retestPassed = evidence.barPromoted
                || "PROMOTE".equals(evidence.barDecision)
                || "PASSED".equals(evidence.barSimulationStatus);
        boolean paperPassed = "PASSED".equals(evidence.paperStatus);
        boolean paperWeak = "FAILED".equals(evidence.paperStatus) || "BLOCKED".equals(evidence.paperStatus);
        boolean shadowReady = evidence.shadow.closedTrades >= minShadowSamples;
        boolean shadowPassed = shadowReady
                && evidence.shadow.expectancyDollars() >= minShadowExpectancyDollars
                && evidence.shadow.profitFactor() >= minShadowProfitFactor;
        boolean improved = evidence.improvedOver(candidate);

        String reason;
        String decision;
        if (retestPassed && paperPassed && shadowPassed) {
            decision = "PROMOTION_READY";
            reason = "targeted_bar_retest_passed paper_gate_passed shadow_confirmation_passed";
        } else if (attempts >= maxAttempts && !retestPassed) {
            decision = "REJECT";
            reason = "max_retest_attempts_reached targeted_bar_retest_still_not_promotable decision=" +
                    evidence.barDecision + " gate=" + evidence.barGate;
        } else if (attempts >= maxAttempts && paperWeak && !improved) {
            decision = "REJECT";
            reason = "max_retest_attempts_reached paper_gate_weak_or_failed status=" + evidence.paperStatus;
        } else if (retestPassed && !paperPassed) {
            decision = "KEEP_WATCHING";
            reason = "targeted_bar_retest_passed waiting_for_paper_gate status=" + evidence.paperStatus;
        } else if (retestPassed && !shadowPassed) {
            decision = "KEEP_WATCHING";
            reason = "targeted_bar_retest_passed waiting_for_shadow_confirmation closedTrades=" + evidence.shadow.closedTrades;
        } else {
            decision = "KEEP_WATCHING";
            reason = improved
                    ? "candidate_improved_but_not_promotion_ready"
                    : "candidate_still_needs_more_historical_paper_or_shadow_confirmation";
        }
        return new RetestDecision(candidate, evidence, attempts, decision, improved, reason);
    }

    private PolygonBarByBarSimulationEngine.Result runTargetedReplay(List<WatchlistCandidate> candidates) {
        Set<String> strategies = new LinkedHashSet<>();
        for (WatchlistCandidate candidate : candidates) {
            if (!candidate.strategy.isBlank()) {
                strategies.add(candidate.strategy);
            }
        }
        if (strategies.isEmpty()) {
            return null;
        }

        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("BAR_SIM_STRATEGIES", String.join(",", strategies));
        overrides.put("BAR_SIM_MAX_STRATEGIES", Integer.toString(strategies.size()));
        overrides.put("BAR_BY_BAR_SIMULATION_POLICY_PATH", retestBarPolicyPath.toString());
        overrides.put("BAR_BY_BAR_SIMULATION_REPORT_PATH", retestBarReportPath.toString());
        overrides.put("BAR_BY_BAR_SIMULATION_TRADES_PATH", retestBarTradesPath.toString());
        overrides.put("BAR_BY_BAR_CANDIDATE_WATCHLIST_PATH", retestBarWatchlistPath.toString());
        overrides.put("BAR_SIM_CANDIDATE_WATCHLIST_ENABLED", "true");

        Map<String, String> previous = new HashMap<>();
        try {
            for (Map.Entry<String, String> entry : overrides.entrySet()) {
                previous.put(entry.getKey(), System.getProperty(entry.getKey()));
                System.setProperty(entry.getKey(), entry.getValue());
            }
            return new PolygonBarByBarSimulationEngine().run();
        } catch (Exception e) {
            System.out.println("CANDIDATE RETEST TARGETED REPLAY FAILED: " + e.getMessage());
            return null;
        } finally {
            for (Map.Entry<String, String> entry : previous.entrySet()) {
                if (entry.getValue() == null) {
                    System.clearProperty(entry.getKey());
                } else {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private List<WatchlistCandidate> readWatchlist() {
        if (!Files.exists(watchlistPath)) {
            return List.of();
        }
        List<WatchlistCandidate> candidates = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(watchlistPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return List.of();
            }
            CsvHeader h = new CsvHeader(header);
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cols = parseCsv(line);
                String strategy = normalizeStrategy(h.get(cols, "strategy"));
                if (strategy.isBlank()) {
                    continue;
                }
                candidates.add(new WatchlistCandidate(
                        h.get(cols, "updatedAt"),
                        strategy,
                        h.get(cols, "gate"),
                        parseDouble(h.get(cols, "priorityScore"), 0.0),
                        parseInt(h.get(cols, "trades"), 0),
                        parseDouble(h.get(cols, "pnlDollars"), 0.0),
                        parseInt(h.get(cols, "validationTrades"), 0),
                        parseDouble(h.get(cols, "validationPnlDollars"), 0.0),
                        parseDouble(h.get(cols, "walkForwardWorstPnlDollars"), 0.0),
                        parseDouble(h.get(cols, "walkForwardWorstDrawdownDollars"), 0.0),
                        h.get(cols, "reason")
                ));
            }
        } catch (Exception e) {
            System.out.println("CANDIDATE WATCHLIST READ FAILED: " + watchlistPath + " " + e.getMessage());
        }
        return candidates;
    }

    private Map<String, ShadowStats> readShadowOutcomes() {
        Map<String, ShadowStats> stats = new LinkedHashMap<>();
        if (!Files.exists(shadowOutcomePath)) {
            return stats;
        }
        try (BufferedReader reader = Files.newBufferedReader(shadowOutcomePath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return stats;
            }
            CsvHeader h = new CsvHeader(header);
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cols = parseCsv(line);
                if (!"CLOSE".equals(normalize(h.get(cols, "eventType")))) {
                    continue;
                }
                String strategy = normalizeStrategy(h.get(cols, "strategyName"));
                if (strategy.isBlank()) {
                    continue;
                }
                double pnl = parseDouble(h.get(cols, "realizedPnlDollars"), 0.0);
                stats.computeIfAbsent(strategy, ShadowStats::new).record(pnl);
            }
        } catch (Exception e) {
            System.out.println("CANDIDATE RETEST SHADOW OUTCOME READ FAILED: " + shadowOutcomePath + " " + e.getMessage());
        }
        return stats;
    }

    private void updateState(Properties state, RetestDecision decision, String firstSeen) {
        String prefix = statePrefix(decision.candidate.strategy);
        state.setProperty(prefix + "firstSeen", firstSeen == null ? "" : firstSeen);
        state.setProperty(prefix + "lastSeen", Instant.now().toString());
        state.setProperty(prefix + "attempts", Integer.toString(decision.attempts));
        state.setProperty(prefix + "decision", decision.decision);
        state.setProperty(prefix + "gate", decision.evidence.barGate);
        state.setProperty(prefix + "paperStatus", decision.evidence.paperStatus);
        state.setProperty(prefix + "shadowClosedTrades", Integer.toString(decision.evidence.shadow.closedTrades));
        state.setProperty(prefix + "improved", Boolean.toString(decision.improved));
        state.setProperty(prefix + "reason", decision.reason);
    }

    private void writeQueue(List<RetestDecision> decisions) {
        StringBuilder b = new StringBuilder();
        b.append("updatedAt,strategy,attempts,decision,improved,watchlistGate,retestStatus,retestDecision,retestGate,paperStatus,shadowClosedTrades,shadowExpectancyDollars,shadowProfitFactor,barPromoted,retestAction,reason\n");
        for (RetestDecision decision : decisions) {
            b.append(decision.toCsvLine()).append('\n');
        }
        writeString(queuePath, b.toString());
    }

    private void writeReport(List<RetestDecision> decisions,
                             PolygonBarByBarSimulationEngine.Result targetedReplay,
                             boolean replayRan,
                             long startedMs,
                             long finishedMs) {
        StringBuilder b = new StringBuilder();
        b.append("CANDIDATE WATCHLIST RETEST QUEUE REPORT\n");
        b.append("mode=OFFLINE_NO_ORDERS\n");
        b.append("startedAt=").append(Instant.ofEpochMilli(startedMs)).append('\n');
        b.append("finishedAt=").append(Instant.ofEpochMilli(finishedMs)).append('\n');
        b.append("elapsedMs=").append(finishedMs - startedMs).append('\n');
        b.append("sourceWatchlist=").append(watchlistPath).append('\n');
        b.append("targetedReplayEnabled=").append(targetedReplayEnabled).append('\n');
        b.append("targetedReplayRan=").append(replayRan).append('\n');
        b.append("targetedReplaySummary=").append(targetedReplay == null ? "none" : targetedReplay.summary()).append('\n');
        b.append("paperPolicy=").append(paperPolicyPath).append('\n');
        b.append("shadowOutcomePath=").append(shadowOutcomePath).append('\n');
        b.append("maxAttempts=").append(maxAttempts).append('\n');
        b.append("minShadowSamples=").append(minShadowSamples).append('\n');
        b.append('\n').append("DECISIONS\n");
        if (decisions.isEmpty()) {
            b.append("- none\n");
        } else {
            for (RetestDecision decision : decisions) {
                b.append("- ").append(decision.candidate.strategy)
                        .append(" decision=").append(decision.decision)
                        .append(" attempts=").append(decision.attempts)
                        .append(" improved=").append(decision.improved)
                        .append(" watchlistGate=").append(decision.candidate.gate)
                        .append(" retestStatus=").append(decision.evidence.barSimulationStatus)
                        .append(" retestDecision=").append(decision.evidence.barDecision)
                        .append(" retestGate=").append(decision.evidence.barGate)
                        .append(" paperStatus=").append(decision.evidence.paperStatus)
                        .append(" shadowClosedTrades=").append(decision.evidence.shadow.closedTrades)
                        .append(" shadowExpectancy=").append(fmt(decision.evidence.shadow.expectancyDollars()))
                        .append(" reason=").append(decision.reason)
                        .append('\n');
            }
        }
        writeString(reportPath, b.toString());
    }

    private void writePolicy(List<RetestDecision> decisions, boolean replayRan) {
        Properties p = new Properties();
        p.setProperty("updatedAt", Instant.now().toString());
        p.setProperty("source", "candidate_watchlist_retest_queue");
        p.setProperty("watchlistPath", watchlistPath.toString());
        p.setProperty("queuePath", queuePath.toString());
        p.setProperty("reportPath", reportPath.toString());
        p.setProperty("targetedReplayRan", Boolean.toString(replayRan));
        p.setProperty("maxAttempts", Integer.toString(maxAttempts));
        p.setProperty("minShadowSamples", Integer.toString(minShadowSamples));
        p.setProperty("candidateCount", Integer.toString(decisions.size()));
        int promotionReady = 0;
        int rejected = 0;
        int keepWatching = 0;
        for (RetestDecision decision : decisions) {
            String prefix = "candidateRetest.strategy." + decision.candidate.strategy + ".";
            p.setProperty(prefix + "decision", decision.decision);
            p.setProperty(prefix + "attempts", Integer.toString(decision.attempts));
            p.setProperty(prefix + "improved", Boolean.toString(decision.improved));
            p.setProperty(prefix + "watchlistGate", decision.candidate.gate);
            p.setProperty(prefix + "retestStatus", decision.evidence.barSimulationStatus);
            p.setProperty(prefix + "retestDecision", decision.evidence.barDecision);
            p.setProperty(prefix + "retestGate", decision.evidence.barGate);
            p.setProperty(prefix + "paperStatus", decision.evidence.paperStatus);
            p.setProperty(prefix + "shadowClosedTrades", Integer.toString(decision.evidence.shadow.closedTrades));
            p.setProperty(prefix + "shadowExpectancyDollars", fmt(decision.evidence.shadow.expectancyDollars()));
            p.setProperty(prefix + "reason", decision.reason);
            if ("PROMOTION_READY".equals(decision.decision)) {
                promotionReady++;
                p.setProperty("candidateRetest.promotionReady." + decision.candidate.strategy, "true");
            } else if ("REJECT".equals(decision.decision)) {
                rejected++;
            } else {
                keepWatching++;
            }
        }
        p.setProperty("promotionReady", Integer.toString(promotionReady));
        p.setProperty("keepWatching", Integer.toString(keepWatching));
        p.setProperty("rejected", Integer.toString(rejected));
        saveProperties(policyPath, p, "Candidate watchlist retest queue policy. Does not directly promote live sizing.");
    }

    private void writeHealth(List<RetestDecision> decisions, boolean replayRan) {
        int promotionReady = 0;
        int keepWatching = 0;
        int rejected = 0;
        for (RetestDecision decision : decisions) {
            if ("PROMOTION_READY".equals(decision.decision)) {
                promotionReady++;
            } else if ("REJECT".equals(decision.decision)) {
                rejected++;
            } else {
                keepWatching++;
            }
        }
        Properties p = new Properties();
        p.setProperty("status", "PASS");
        p.setProperty("updatedAt", Instant.now().toString());
        p.setProperty("queuePath", queuePath.toString());
        p.setProperty("reportPath", reportPath.toString());
        p.setProperty("policyPath", policyPath.toString());
        p.setProperty("targetedReplayRan", Boolean.toString(replayRan));
        p.setProperty("candidates", Integer.toString(decisions.size()));
        p.setProperty("promotionReady", Integer.toString(promotionReady));
        p.setProperty("keepWatching", Integer.toString(keepWatching));
        p.setProperty("rejected", Integer.toString(rejected));
        saveProperties(healthPath, p, "Candidate watchlist retest queue health");
    }

    private static boolean severeGate(String gate) {
        String normalized = normalize(gate);
        return normalized.contains("LOSS_VETO") || normalized.contains("FAILED");
    }

    private static int gateRank(String gate) {
        String normalized = normalize(gate);
        if ("PASSED".equals(normalized)) {
            return 5;
        }
        if ("DISABLED".equals(normalized)) {
            return 4;
        }
        if (normalized.contains("INSUFFICIENT")) {
            return 2;
        }
        if (normalized.contains("FAILED")) {
            return 1;
        }
        if (normalized.contains("LOSS_VETO")) {
            return 0;
        }
        return 1;
    }

    private static String statePrefix(String strategy) {
        return "candidate." + normalizeStrategy(strategy) + ".";
    }

    private static Properties loadProperties(Path path) {
        Properties p = new Properties();
        if (path == null || !Files.exists(path)) {
            return p;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (Exception e) {
            System.out.println("CANDIDATE RETEST PROPERTIES READ FAILED: " + path + " " + e.getMessage());
        }
        return p;
    }

    private static void saveProperties(Path path, Properties p, String comment) {
        try {
            ensureParent(path);
            try (OutputStream out = Files.newOutputStream(path)) {
                p.store(out, comment);
            }
        } catch (Exception e) {
            System.out.println("CANDIDATE RETEST PROPERTIES WRITE FAILED: " + path + " " + e.getMessage());
        }
    }

    private static void writeString(Path path, String value) {
        try {
            ensureParent(path);
            Files.writeString(path, value == null ? "" : value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("CANDIDATE RETEST WRITE FAILED: " + path + " " + e.getMessage());
        }
    }

    private static void ensureParent(Path path) throws Exception {
        Path parent = path == null ? null : path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else if (c == '"') {
                quoted = true;
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String normalizeStrategy(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean isTrue(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = env(key, "");
            return value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = env(key, "");
            return value.isBlank() ? fallback : Double.parseDouble(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean envBool(String key, boolean fallback) {
        String value = env(key, "");
        if (value.isBlank()) {
            return fallback;
        }
        return isTrue(value);
    }

    private static String env(String key, String fallback) {
        String property = System.getProperty(key);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.6f", Double.isFinite(value) ? value : 0.0);
    }

    public static final class Result {
        public final int candidates;
        public final boolean targetedReplayRan;
        public final int promotionReady;
        public final int keepWatching;
        public final int rejected;
        public final Path queuePath;
        public final Path reportPath;
        public final Path policyPath;
        public final Path healthPath;
        public final long elapsedMs;

        Result(int candidates, boolean targetedReplayRan, int promotionReady, int keepWatching, int rejected,
               Path queuePath, Path reportPath, Path policyPath, Path healthPath, long elapsedMs) {
            this.candidates = candidates;
            this.targetedReplayRan = targetedReplayRan;
            this.promotionReady = promotionReady;
            this.keepWatching = keepWatching;
            this.rejected = rejected;
            this.queuePath = queuePath;
            this.reportPath = reportPath;
            this.policyPath = policyPath;
            this.healthPath = healthPath;
            this.elapsedMs = elapsedMs;
        }

        public String summary() {
            return "candidates=" + candidates +
                    " targetedReplayRan=" + targetedReplayRan +
                    " promotionReady=" + promotionReady +
                    " keepWatching=" + keepWatching +
                    " rejected=" + rejected +
                    " queuePath=" + queuePath +
                    " reportPath=" + reportPath +
                    " elapsedMs=" + elapsedMs;
        }
    }

    private static final class RetestDecision {
        final WatchlistCandidate candidate;
        final Evidence evidence;
        final int attempts;
        final String decision;
        final boolean improved;
        final String reason;

        RetestDecision(WatchlistCandidate candidate, Evidence evidence, int attempts,
                       String decision, boolean improved, String reason) {
            this.candidate = candidate;
            this.evidence = evidence;
            this.attempts = attempts;
            this.decision = decision == null ? "KEEP_WATCHING" : decision;
            this.improved = improved;
            this.reason = reason == null ? "" : reason;
        }

        int rank() {
            if ("PROMOTION_READY".equals(decision)) {
                return 3;
            }
            if ("KEEP_WATCHING".equals(decision)) {
                return 2;
            }
            return 1;
        }

        String toCsvLine() {
            return csv(Instant.now().toString()) +
                    "," + csv(candidate.strategy) +
                    "," + attempts +
                    "," + csv(decision) +
                    "," + improved +
                    "," + csv(candidate.gate) +
                    "," + csv(evidence.barSimulationStatus) +
                    "," + csv(evidence.barDecision) +
                    "," + csv(evidence.barGate) +
                    "," + csv(evidence.paperStatus) +
                    "," + evidence.shadow.closedTrades +
                    "," + fmt(evidence.shadow.expectancyDollars()) +
                    "," + fmt(evidence.shadow.profitFactor()) +
                    "," + evidence.barPromoted +
                    "," + csv("RETEST_AFTER_MORE_HISTORICAL_AND_PAPER_SAMPLES") +
                    "," + csv(reason);
        }
    }

    private static final class Evidence {
        final String barSimulationStatus;
        final String barDecision;
        final String barGate;
        final double barWorstPnl;
        final double barWorstDrawdown;
        final boolean barPromoted;
        final String paperStatus;
        final ShadowStats shadow;

        private Evidence(String barSimulationStatus,
                         String barDecision,
                         String barGate,
                         double barWorstPnl,
                         double barWorstDrawdown,
                         boolean barPromoted,
                         String paperStatus,
                         ShadowStats shadow) {
            this.barSimulationStatus = barSimulationStatus;
            this.barDecision = barDecision;
            this.barGate = barGate;
            this.barWorstPnl = barWorstPnl;
            this.barWorstDrawdown = barWorstDrawdown;
            this.barPromoted = barPromoted;
            this.paperStatus = paperStatus;
            this.shadow = shadow == null ? ShadowStats.empty("") : shadow;
        }

        static Evidence from(WatchlistCandidate candidate,
                             Properties barPolicy,
                             Properties paperPolicy,
                             ShadowStats shadow) {
            String strategy = candidate.strategy;
            String strategyPrefix = "strategy." + strategy + ".";
            String barDecision = firstNonBlank(barPolicy.getProperty(strategyPrefix + "decision"), "UNKNOWN");
            String barStatus = firstNonBlank(
                    barPolicy.getProperty("simulationStatus." + strategy),
                    barPolicy.getProperty("barSim.watchlistStatus." + strategy),
                    barDecision);
            String gate = firstNonBlank(
                    barPolicy.getProperty(strategyPrefix + "walkForwardStatus"),
                    barPolicy.getProperty("candidateWatchlist.strategy." + strategy + ".gate"),
                    candidate.gate);
            double worstPnl = parseDouble(barPolicy.getProperty(strategyPrefix + "walkForwardWorstWindowPnlDollars"),
                    candidate.walkForwardWorstPnl);
            double worstDrawdown = parseDouble(barPolicy.getProperty(strategyPrefix + "walkForwardWorstWindowDrawdownDollars"),
                    candidate.walkForwardWorstDrawdown);
            boolean promoted = isTrue(barPolicy.getProperty("promotedStrategy." + strategy));
            String paperStatus = firstNonBlank(paperPolicy.getProperty("paperStatus." + strategy), "UNKNOWN");
            return new Evidence(barStatus, barDecision, gate, worstPnl, worstDrawdown, promoted, paperStatus, shadow);
        }

        boolean improvedOver(WatchlistCandidate candidate) {
            return barPromoted
                    || "PASSED".equals(barSimulationStatus)
                    || gateRank(barGate) > gateRank(candidate.gate)
                    || (barWorstPnl > candidate.walkForwardWorstPnl
                    && barWorstDrawdown <= candidate.walkForwardWorstDrawdown);
        }
    }

    private static final class WatchlistCandidate {
        final String updatedAt;
        final String strategy;
        final String gate;
        final double priorityScore;
        final int trades;
        final double pnl;
        final int validationTrades;
        final double validationPnl;
        final double walkForwardWorstPnl;
        final double walkForwardWorstDrawdown;
        final String reason;

        WatchlistCandidate(String updatedAt,
                           String strategy,
                           String gate,
                           double priorityScore,
                           int trades,
                           double pnl,
                           int validationTrades,
                           double validationPnl,
                           double walkForwardWorstPnl,
                           double walkForwardWorstDrawdown,
                           String reason) {
            this.updatedAt = updatedAt == null ? "" : updatedAt;
            this.strategy = normalizeStrategy(strategy);
            this.gate = gate == null ? "" : gate;
            this.priorityScore = priorityScore;
            this.trades = trades;
            this.pnl = pnl;
            this.validationTrades = validationTrades;
            this.validationPnl = validationPnl;
            this.walkForwardWorstPnl = walkForwardWorstPnl;
            this.walkForwardWorstDrawdown = walkForwardWorstDrawdown;
            this.reason = reason == null ? "" : reason;
        }
    }

    private static final class ShadowStats {
        final String strategy;
        int closedTrades;
        int wins;
        double pnl;
        double grossProfit;
        double grossLoss;

        ShadowStats(String strategy) {
            this.strategy = strategy == null ? "" : strategy;
        }

        static ShadowStats empty(String strategy) {
            return new ShadowStats(strategy);
        }

        void record(double pnlDollars) {
            closedTrades++;
            pnl += pnlDollars;
            if (pnlDollars > 0.0) {
                wins++;
                grossProfit += pnlDollars;
            } else if (pnlDollars < 0.0) {
                grossLoss += Math.abs(pnlDollars);
            }
        }

        double expectancyDollars() {
            return closedTrades <= 0 ? 0.0 : pnl / closedTrades;
        }

        double profitFactor() {
            if (grossLoss <= 0.0) {
                return grossProfit > 0.0 ? 99.0 : 0.0;
            }
            return grossProfit / grossLoss;
        }
    }

    private static final class CsvHeader {
        private final Map<String, Integer> indexes = new HashMap<>();

        CsvHeader(String header) {
            List<String> cols = parseCsv(header);
            for (int i = 0; i < cols.size(); i++) {
                indexes.put(cols.get(i).trim(), i);
            }
        }

        String get(List<String> cols, String name) {
            Integer idx = indexes.get(name);
            if (idx == null || idx < 0 || idx >= cols.size()) {
                return "";
            }
            return cols.get(idx);
        }
    }
}
