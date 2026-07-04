package com.bot.intelligence;

import com.bot.master.StrategyAction;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;

import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Final live-capital readiness gate. It combines execution cost, calibration,
 * regime, paper/shadow performance, exit quality, and daily risk before a real
 * order is allowed to leave the master engine.
 */
public final class LiveTradeReadinessGate {
    private static final LiveTradeReadinessGate INSTANCE = new LiveTradeReadinessGate();
    private static final String JOURNAL_HEADER = "timestamp,ticker,strategy,mode,liveAllowed,sizingMultiplier,score,expectedMoveFraction,expectedNetEdgeFraction,paperStatus,paperSamples,paperExpectancyPercent,paperProfitFactor,exitStyle,exitSamples,exitExpectancyPercent,exitProfitFactor,reason\n";

    private final ExecutionCostModel executionCostModel = ExecutionCostModel.getInstance();
    private final PreTradeCalibrationModel preTradeCalibrationModel = PreTradeCalibrationModel.getInstance();
    private final StrategySelectionGovernor strategySelectionGovernor = StrategySelectionGovernor.getInstance();
    private final Path paperPolicyPath = Path.of(env("PAPER_TRADING_STRATEGY_POLICY_PATH",
            "logs/paper_trading_strategy_policy.properties"));
    private final Path exitPolicyPath = Path.of(env("EXIT_SHADOW_TOURNAMENT_POLICY_PATH",
            "logs/exit_shadow_tournament_policy.properties"));
    private final Path journalPath = Path.of(env("LIVE_TRADE_READINESS_JOURNAL_PATH",
            "logs/live_trade_readiness_journal.csv"));
    private final Path healthPath = Path.of(env("LIVE_TRADE_READINESS_HEALTH_PATH",
            "logs/live_trade_readiness_health.properties"));
    private final Path reportPath = Path.of(env("LIVE_TRADE_READINESS_REPORT_PATH",
            "logs/live_trade_readiness_report.txt"));
    private final Map<String, PaperProfile> paperProfiles = new ConcurrentHashMap<>();
    private final Map<String, ExitProfile> exitProfiles = new ConcurrentHashMap<>();
    private volatile ExitProfile globalExitProfile = ExitProfile.none("GLOBAL");
    private volatile long lastLoadAtMs = 0L;

    private LiveTradeReadinessGate() {
    }

    public static LiveTradeReadinessGate getInstance() {
        return INSTANCE;
    }

    public ReadinessReview review(StrategySignal signal,
                                  StrategyContext context,
                                  ExecutionCostModel.CostReview costReview,
                                  PreTradeCalibrationModel.CalibrationReview calibrationReview,
                                  double accountEquity,
                                  double sessionStartingEquity) {
        if (!envBool("LIVE_TRADE_READINESS_GATE_ENABLED", true)) {
            return ReadinessReview.live(
                    Mode.LIVE_FULL,
                    signal,
                    1.0,
                    1.0,
                    0.0,
                    0.0,
                    PaperProfile.unknown(signal == null ? "UNKNOWN" : signal.getStrategyName()),
                    ExitProfile.none("GLOBAL"),
                    "live readiness gate disabled"
            );
        }
        if (signal == null || signal.getAction() != StrategyAction.BUY || !signal.isActionableBuy()) {
            return ReadinessReview.block(signal, 0.0, 0.0,
                    PaperProfile.unknown("UNKNOWN"), ExitProfile.none("GLOBAL"),
                    "no actionable buy signal");
        }

        reloadIfNeeded();
        String strategy = normalizeStrategy(signal.getStrategyName());
        PaperProfile paper = paperProfiles.getOrDefault(strategy, PaperProfile.unknown(strategy));
        ExitProfile exit = exitProfiles.getOrDefault(strategy, globalExitProfile);
        if (exit == null) {
            exit = ExitProfile.none("GLOBAL");
        }
        ExecutionCostModel.CostReview cost = costReview == null
                ? executionCostModel.review(signal, context)
                : costReview;
        PreTradeCalibrationModel.CalibrationReview calibration = calibrationReview == null
                ? preTradeCalibrationModel.review(signal, context)
                : calibrationReview;
        StrategySelectionGovernor.RegimeSizingReview regime =
                strategySelectionGovernor.regimeSizingReview(strategy);

        double expectedMove = normalizeExpectedMove(signal.getExpectedMovePercent());
        double costFraction = cost == null ? 0.0 : cost.roundTripCostFraction;
        double netEdge = Math.max(0.0, expectedMove - costFraction);
        if (cost != null && !cost.approved) {
            return ReadinessReview.block(signal, expectedMove, netEdge, paper, exit,
                    "execution cost veto: " + cost.reason);
        }
        if (calibration != null && !calibration.approved) {
            return ReadinessReview.block(signal, expectedMove, netEdge, paper, exit,
                    "pre-trade calibration veto: " + calibration.reason);
        }
        if (regime != null && regime.disabled) {
            return ReadinessReview.block(signal, expectedMove, netEdge, paper, exit,
                    "regime strategy veto: " + regime.summary());
        }

        double minNetEdge = envDouble("LIVE_READINESS_MIN_NET_EDGE_FRACTION", 0.0015);
        if (netEdge < minNetEdge) {
            return ReadinessReview.block(signal, expectedMove, netEdge, paper, exit,
                    "after-cost edge too small expected=" + fmt(expectedMove) +
                            " cost=" + fmt(costFraction) +
                            " net=" + fmt(netEdge) +
                            " min=" + fmt(minNetEdge));
        }

        double fillRate = cost == null ? 1.0 : cost.fillRate;
        double minFillRate = envDouble("LIVE_READINESS_MIN_FILL_RATE", 0.72);
        if (fillRate < minFillRate) {
            return readinessMode(env("LIVE_READINESS_LOW_FILL_MODE", "PAPER_ONLY"), signal, expectedMove, netEdge,
                    0.0, paper, exit,
                    "execution fill rate below live threshold fillRate=" + fmt(fillRate) +
                            " min=" + fmt(minFillRate));
        }

        double dailyRiskMultiplier = 1.0;
        if (accountEquity > 0.0 && sessionStartingEquity > 0.0) {
            double drawdown = (sessionStartingEquity - accountEquity) / sessionStartingEquity;
            double blockDrawdown = envDouble("LIVE_READINESS_DAILY_DRAWDOWN_BLOCK", 0.0125);
            double reduceDrawdown = envDouble("LIVE_READINESS_DAILY_DRAWDOWN_REDUCE", 0.0075);
            if (drawdown >= blockDrawdown) {
                return ReadinessReview.block(signal, expectedMove, netEdge, paper, exit,
                        "daily drawdown live block drawdown=" + fmt(drawdown) +
                                " limit=" + fmt(blockDrawdown));
            }
            if (drawdown >= reduceDrawdown) {
                dailyRiskMultiplier = Math.min(dailyRiskMultiplier,
                        envDouble("LIVE_READINESS_DRAWDOWN_SIZE_MULTIPLIER", 0.35));
            }
        }

        String paperStatus = normalizeStatus(paper.paperStatus);
        if ("BLOCKED".equals(paperStatus)) {
            return readinessMode(env("LIVE_READINESS_PAPER_BLOCKED_MODE", "SHADOW_ONLY"), signal,
                    expectedMove, netEdge, 0.0, paper, exit,
                    "paper/shadow performance gate blocked strategy: " + paper.reason);
        }
        if (("INSUFFICIENT".equals(paperStatus) || "UNKNOWN".equals(paperStatus)) && !paperPassesMetrics(paper)) {
            if (envBool("LIVE_READINESS_ALLOW_TINY_LIVE_ON_INSUFFICIENT", false)
                    && signal.getConfidence() >= envDouble("LIVE_READINESS_TINY_LIVE_MIN_CONFIDENCE", 0.82)
                    && netEdge >= envDouble("LIVE_READINESS_TINY_LIVE_MIN_NET_EDGE", 0.0040)
                    && exitPassesMetrics(exit)) {
                double tiny = envDouble("LIVE_READINESS_TINY_SIZE_MULTIPLIER", 0.10);
                return ReadinessReview.live(Mode.LIVE_REDUCED, signal, score(signal, netEdge, paper, exit),
                        clamp(tiny, 0.01, 0.25), expectedMove, netEdge, paper, exit,
                        "insufficient paper sample, but tiny-live override cleared");
            }
            return readinessMode(env("LIVE_READINESS_INSUFFICIENT_MODE", "PAPER_ONLY"), signal,
                    expectedMove, netEdge, 0.0, paper, exit,
                    "strategy still needs paper/shadow proof: status=" + paperStatus +
                            " samples=" + paper.closedTrades);
        }
        if (!paperPassesMetrics(paper)) {
            return readinessMode(env("LIVE_READINESS_WEAK_PAPER_MODE", "PAPER_ONLY"), signal,
                    expectedMove, netEdge, 0.0, paper, exit,
                    "paper metrics below live threshold: " + paper.summary());
        }

        boolean exitReady = exitPassesMetrics(exit);
        if (!exitReady && envBool("LIVE_READINESS_REQUIRE_POSITIVE_EXIT_POLICY", false)) {
            return readinessMode(env("LIVE_READINESS_WEAK_EXIT_MODE", "PAPER_ONLY"), signal,
                    expectedMove, netEdge, 0.0, paper, exit,
                    "exit policy has not proven positive edge: " + exit.summary());
        }

        double multiplier = 1.0;
        multiplier = Math.min(multiplier, cost == null ? 1.0 : cost.sizingMultiplier);
        multiplier = Math.min(multiplier, calibration == null ? 1.0 : calibration.sizingMultiplier);
        multiplier = Math.min(multiplier, regime == null ? 1.0 : regime.sizingMultiplier);
        multiplier = Math.min(multiplier, paper.sizingMultiplier);
        multiplier = Math.min(multiplier, dailyRiskMultiplier);
        if (!exitReady) {
            multiplier = Math.min(multiplier, envDouble("LIVE_READINESS_UNPROVEN_EXIT_SIZE_MULTIPLIER", 0.65));
        }

        double score = score(signal, netEdge, paper, exit);
        boolean fullSize = "PASSED".equals(paperStatus)
                && exitReady
                && netEdge >= envDouble("LIVE_READINESS_FULL_MIN_NET_EDGE_FRACTION", 0.0030)
                && signal.getConfidence() >= envDouble("LIVE_READINESS_FULL_MIN_CONFIDENCE", 0.72)
                && multiplier >= envDouble("LIVE_READINESS_FULL_MIN_SIZE_MULTIPLIER", 0.95);
        if (fullSize) {
            return ReadinessReview.live(Mode.LIVE_FULL, signal, score, clamp(multiplier, 0.05, 1.25),
                    expectedMove, netEdge, paper, exit,
                    "all live readiness checks passed");
        }

        multiplier = Math.min(multiplier, envDouble("LIVE_READINESS_REDUCED_MAX_SIZE_MULTIPLIER", 0.50));
        multiplier = Math.max(envDouble("LIVE_READINESS_REDUCED_MIN_SIZE_MULTIPLIER", 0.05), multiplier);
        return ReadinessReview.live(Mode.LIVE_REDUCED, signal, score, clamp(multiplier, 0.01, 0.75),
                expectedMove, netEdge, paper, exit,
                "live allowed at reduced size while edge matures");
    }

    public synchronized void recordReview(ReadinessReview review) {
        if (review == null || !envBool("LIVE_READINESS_JOURNAL_ENABLED", true)) {
            return;
        }
        try {
            Path parent = journalPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            boolean writeHeader = !Files.exists(journalPath) || Files.size(journalPath) == 0L;
            StringBuilder b = new StringBuilder();
            if (writeHeader) {
                b.append(JOURNAL_HEADER);
            }
            b.append(Instant.now()).append(',')
                    .append(csv(review.ticker)).append(',')
                    .append(csv(review.strategy)).append(',')
                    .append(review.mode).append(',')
                    .append(review.allowsLiveOrder()).append(',')
                    .append(fmt(review.sizingMultiplier)).append(',')
                    .append(fmt(review.score)).append(',')
                    .append(fmt(review.expectedMoveFraction)).append(',')
                    .append(fmt(review.expectedNetEdgeFraction)).append(',')
                    .append(csv(review.paperStatus)).append(',')
                    .append(review.paperSamples).append(',')
                    .append(fmt(review.paperExpectancyPercent)).append(',')
                    .append(fmt(review.paperProfitFactor)).append(',')
                    .append(csv(review.exitStyle)).append(',')
                    .append(review.exitSamples).append(',')
                    .append(fmt(review.exitExpectancyPercent)).append(',')
                    .append(fmt(review.exitProfitFactor)).append(',')
                    .append(csv(review.reason)).append('\n');
            Files.writeString(journalPath, b.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.out.println("LIVE READINESS JOURNAL WRITE FAILED: " + e.getMessage());
        }
    }

    public Result writeHealthSnapshot() {
        reloadIfNeeded(true);
        int passed = 0;
        int blocked = 0;
        int insufficient = 0;
        int unknown = 0;
        for (PaperProfile profile : paperProfiles.values()) {
            String status = normalizeStatus(profile.paperStatus);
            if ("PASSED".equals(status)) {
                passed++;
            } else if ("BLOCKED".equals(status)) {
                blocked++;
            } else if ("INSUFFICIENT".equals(status)) {
                insufficient++;
            } else {
                unknown++;
            }
        }
        int exitPositive = 0;
        int exitWeak = 0;
        for (ExitProfile profile : exitProfiles.values()) {
            if (exitPassesMetrics(profile)) {
                exitPositive++;
            } else {
                exitWeak++;
            }
        }

        Result result = new Result(paperProfiles.size(), passed, blocked, insufficient, unknown,
                exitProfiles.size(), exitPositive, exitWeak, healthPath, reportPath, journalPath);
        ensureJournalFile();
        writeHealth(result);
        writeReport(result);
        return result;
    }

    private void reloadIfNeeded() {
        reloadIfNeeded(false);
    }

    private synchronized void reloadIfNeeded(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastLoadAtMs < Math.max(1_000L, envLong("LIVE_READINESS_POLICY_RELOAD_MS", 30_000L))) {
            return;
        }
        lastLoadAtMs = now;
        loadPaperPolicy();
        loadExitPolicy();
    }

    private void loadPaperPolicy() {
        Map<String, PaperProfile> loaded = new ConcurrentHashMap<>();
        if (Files.exists(paperPolicyPath)) {
            try (InputStream in = Files.newInputStream(paperPolicyPath)) {
                Properties p = new Properties();
                p.load(in);
                for (String key : p.stringPropertyNames()) {
                    if (!key.startsWith("paperStatus.")) {
                        continue;
                    }
                    String strategy = normalizeStrategy(key.substring("paperStatus.".length()));
                    if (!strategy.isBlank()) {
                        loaded.put(strategy, PaperProfile.from(p, strategy));
                    }
                }
            } catch (Exception e) {
                System.out.println("LIVE READINESS PAPER POLICY LOAD FAILED: " + paperPolicyPath + " " + e.getMessage());
            }
        }
        paperProfiles.clear();
        paperProfiles.putAll(loaded);
    }

    private void loadExitPolicy() {
        Map<String, ExitProfile> loaded = new ConcurrentHashMap<>();
        ExitProfile loadedGlobal = ExitProfile.none("GLOBAL");
        if (Files.exists(exitPolicyPath)) {
            try (InputStream in = Files.newInputStream(exitPolicyPath)) {
                Properties p = new Properties();
                p.load(in);
                loadedGlobal = ExitProfile.from(p, "global.", "GLOBAL");
                for (String key : p.stringPropertyNames()) {
                    String prefix = "strategy.";
                    String suffix = ".exitStyle";
                    if (!key.startsWith(prefix) || !key.endsWith(suffix)) {
                        continue;
                    }
                    String strategy = normalizeStrategy(key.substring(prefix.length(), key.length() - suffix.length()));
                    if (!strategy.isBlank()) {
                        loaded.put(strategy, ExitProfile.from(p, "strategy." + strategy + ".", strategy));
                    }
                }
            } catch (Exception e) {
                System.out.println("LIVE READINESS EXIT POLICY LOAD FAILED: " + exitPolicyPath + " " + e.getMessage());
            }
        }
        exitProfiles.clear();
        exitProfiles.putAll(loaded);
        globalExitProfile = loadedGlobal;
    }

    private boolean paperPassesMetrics(PaperProfile profile) {
        if (profile == null) {
            return false;
        }
        String status = normalizeStatus(profile.paperStatus);
        if ("BLOCKED".equals(status)) {
            return false;
        }
        if ("PASSED".equals(status)) {
            return true;
        }
        return profile.closedTrades >= envInt("LIVE_READINESS_MIN_PAPER_SAMPLES_FOR_LIVE", 20)
                && profile.expectancyPercent >= envDouble("LIVE_READINESS_MIN_PAPER_EXPECTANCY_PERCENT", 0.0)
                && profile.profitFactor >= envDouble("LIVE_READINESS_MIN_PAPER_PROFIT_FACTOR", 1.05)
                && profile.winRate >= envDouble("LIVE_READINESS_MIN_PAPER_WIN_RATE", 0.10);
    }

    private boolean exitPassesMetrics(ExitProfile profile) {
        if (profile == null || !profile.hasPolicy) {
            return false;
        }
        return profile.samples >= envLong("LIVE_READINESS_MIN_EXIT_SAMPLES", 8L)
                && profile.expectancyPercent > envDouble("LIVE_READINESS_MIN_EXIT_EXPECTANCY_PERCENT", 0.0)
                && profile.profitFactor >= envDouble("LIVE_READINESS_MIN_EXIT_PROFIT_FACTOR", 1.0);
    }

    private ReadinessReview readinessMode(String modeName,
                                          StrategySignal signal,
                                          double expectedMove,
                                          double netEdge,
                                          double score,
                                          PaperProfile paper,
                                          ExitProfile exit,
                                          String reason) {
        Mode mode = Mode.from(modeName);
        if (mode == Mode.LIVE_FULL || mode == Mode.LIVE_REDUCED) {
            double multiplier = mode == Mode.LIVE_FULL
                    ? 1.0
                    : envDouble("LIVE_READINESS_REDUCED_MAX_SIZE_MULTIPLIER", 0.50);
            return ReadinessReview.live(mode, signal, score, multiplier, expectedMove, netEdge, paper, exit, reason);
        }
        return new ReadinessReview(mode, signal, score, 0.0, expectedMove, netEdge, paper, exit, reason);
    }

    private static double score(StrategySignal signal, double netEdge, PaperProfile paper, ExitProfile exit) {
        double confidence = signal == null ? 0.0 : signal.getConfidence();
        double netScore = clamp(netEdge / 0.0060, 0.0, 1.0);
        double paperScore = paper == null ? 0.0 : clamp((paper.profitFactor - 1.0) / 2.0, 0.0, 1.0);
        double exitScore = exit == null ? 0.0 : clamp((exit.profitFactor - 1.0) / 2.0, 0.0, 1.0);
        return clamp(confidence * 0.35 + netScore * 0.35 + paperScore * 0.20 + exitScore * 0.10, 0.0, 1.0);
    }

    private void writeHealth(Result result) {
        try {
            ensureParent(healthPath);
            Properties p = new Properties();
            p.setProperty("status", "PASS");
            p.setProperty("generatedAt", Instant.now().toString());
            p.setProperty("gateEnabled", Boolean.toString(envBool("LIVE_TRADE_READINESS_GATE_ENABLED", true)));
            p.setProperty("paperStrategies", Integer.toString(result.paperStrategies));
            p.setProperty("paperPassed", Integer.toString(result.paperPassed));
            p.setProperty("paperBlocked", Integer.toString(result.paperBlocked));
            p.setProperty("paperInsufficient", Integer.toString(result.paperInsufficient));
            p.setProperty("paperUnknown", Integer.toString(result.paperUnknown));
            p.setProperty("exitProfiles", Integer.toString(result.exitProfiles));
            p.setProperty("exitPositive", Integer.toString(result.exitPositive));
            p.setProperty("exitWeak", Integer.toString(result.exitWeak));
            p.setProperty("paperPolicyPath", paperPolicyPath.toString());
            p.setProperty("exitPolicyPath", exitPolicyPath.toString());
            p.setProperty("journalPath", journalPath.toString());
            p.setProperty("reportPath", reportPath.toString());
            StringWriter writer = new StringWriter();
            p.store(writer, "Live trade readiness gate health");
            Files.writeString(healthPath, writer.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.out.println("LIVE READINESS HEALTH WRITE FAILED: " + e.getMessage());
        }
    }

    private void writeReport(Result result) {
        try {
            ensureParent(reportPath);
            StringBuilder b = new StringBuilder();
            b.append("LIVE TRADE READINESS GATE REPORT\n");
            b.append("generatedAt=").append(Instant.now()).append('\n');
            b.append("gateEnabled=").append(envBool("LIVE_TRADE_READINESS_GATE_ENABLED", true)).append('\n');
            b.append("paperPolicyPath=").append(paperPolicyPath).append('\n');
            b.append("exitPolicyPath=").append(exitPolicyPath).append('\n');
            b.append("paperStrategies=").append(result.paperStrategies).append('\n');
            b.append("paperPassed=").append(result.paperPassed).append('\n');
            b.append("paperBlocked=").append(result.paperBlocked).append('\n');
            b.append("paperInsufficient=").append(result.paperInsufficient).append('\n');
            b.append("exitProfiles=").append(result.exitProfiles).append('\n');
            b.append("exitPositive=").append(result.exitPositive).append('\n');
            b.append("exitWeak=").append(result.exitWeak).append('\n');
            b.append('\n');
            b.append("Runtime actions: LIVE_FULL, LIVE_REDUCED, PAPER_ONLY, SHADOW_ONLY, BLOCK\n");
            b.append("Default stance: no real money for unknown, insufficient, or paper-blocked strategies.\n");
            Files.writeString(reportPath, b.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.out.println("LIVE READINESS REPORT WRITE FAILED: " + e.getMessage());
        }
    }

    private void ensureJournalFile() {
        try {
            ensureParent(journalPath);
            if (!Files.exists(journalPath) || Files.size(journalPath) == 0L) {
                Files.writeString(journalPath, JOURNAL_HEADER, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (Exception e) {
            System.out.println("LIVE READINESS JOURNAL INIT FAILED: " + e.getMessage());
        }
    }

    private static double normalizeExpectedMove(double value) {
        double expected = Math.abs(Double.isFinite(value) ? value : 0.0);
        return expected > 1.0 ? expected / 100.0 : expected;
    }

    private static String normalizeStrategy(String value) {
        return value == null || value.isBlank()
                ? "UNKNOWN"
                : value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
    }

    private static String normalizeStatus(String value) {
        return value == null || value.isBlank()
                ? "UNKNOWN"
                : value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean envBool(String key, boolean fallback) {
        String value = env(key, "");
        if (value.isBlank()) {
            return fallback;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
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

    private static long envLong(String key, long fallback) {
        try {
            String value = env(key, "");
            return value.isBlank() ? fallback : Long.parseLong(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double envDouble(String key, double fallback) {
        return parseDouble(env(key, ""), fallback);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.6f", Double.isFinite(value) ? value : 0.0);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private static void ensureParent(Path path) throws Exception {
        Path parent = path == null ? null : path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    public enum Mode {
        LIVE_FULL,
        LIVE_REDUCED,
        PAPER_ONLY,
        SHADOW_ONLY,
        BLOCK;

        static Mode from(String value) {
            String normalized = normalizeStatus(value);
            for (Mode mode : values()) {
                if (mode.name().equals(normalized)) {
                    return mode;
                }
            }
            return PAPER_ONLY;
        }
    }

    private static final class PaperProfile {
        final String strategy;
        final String paperStatus;
        final String simulationStatus;
        final int closedTrades;
        final double expectancyPercent;
        final double profitFactor;
        final double winRate;
        final double executionCostFraction;
        final double executionFillRate;
        final double sizingMultiplier;
        final String reason;

        PaperProfile(String strategy,
                     String paperStatus,
                     String simulationStatus,
                     int closedTrades,
                     double expectancyPercent,
                     double profitFactor,
                     double winRate,
                     double executionCostFraction,
                     double executionFillRate,
                     double sizingMultiplier,
                     String reason) {
            this.strategy = normalizeStrategy(strategy);
            this.paperStatus = normalizeStatus(paperStatus);
            this.simulationStatus = normalizeStatus(simulationStatus);
            this.closedTrades = Math.max(0, closedTrades);
            this.expectancyPercent = expectancyPercent;
            this.profitFactor = Math.max(0.0, profitFactor);
            this.winRate = Math.max(0.0, Math.min(1.0, winRate));
            this.executionCostFraction = Math.max(0.0, executionCostFraction);
            this.executionFillRate = Math.max(0.0, Math.min(1.0, executionFillRate));
            this.sizingMultiplier = clamp(sizingMultiplier, 0.0, 1.50);
            this.reason = reason == null ? "" : reason;
        }

        static PaperProfile from(Properties p, String strategy) {
            String prefix = "strategy." + strategy + ".";
            return new PaperProfile(
                    strategy,
                    p.getProperty("paperStatus." + strategy, "UNKNOWN"),
                    p.getProperty("simulationStatus." + strategy, "UNKNOWN"),
                    parseInt(p.getProperty(prefix + "closedTrades"), 0),
                    parseDouble(p.getProperty(prefix + "expectancyPercent"), 0.0),
                    parseDouble(p.getProperty(prefix + "profitFactor"), 0.0),
                    parseDouble(p.getProperty(prefix + "winRate"), 0.0),
                    parseDouble(p.getProperty(prefix + "executionCostFraction"), 0.0),
                    parseDouble(p.getProperty(prefix + "executionFillRate"), 1.0),
                    parseDouble(p.getProperty("strategyMultiplier." + strategy), 1.0),
                    p.getProperty(prefix + "reason", "")
            );
        }

        static PaperProfile unknown(String strategy) {
            return new PaperProfile(strategy, "UNKNOWN", "UNKNOWN", 0, 0.0, 0.0,
                    0.0, 0.0, 1.0, 1.0, "no paper/shadow performance profile");
        }

        String summary() {
            return "paperStatus=" + paperStatus +
                    " samples=" + closedTrades +
                    " expectancy=" + fmt(expectancyPercent) +
                    " profitFactor=" + fmt(profitFactor) +
                    " winRate=" + fmt(winRate);
        }
    }

    private static final class ExitProfile {
        final String key;
        final String exitStyle;
        final long samples;
        final double expectancyPercent;
        final double profitFactor;
        final boolean hasPolicy;

        ExitProfile(String key,
                    String exitStyle,
                    long samples,
                    double expectancyPercent,
                    double profitFactor,
                    boolean hasPolicy) {
            this.key = normalizeStrategy(key);
            this.exitStyle = exitStyle == null || exitStyle.isBlank()
                    ? "STATIC_DEFAULT"
                    : exitStyle.trim().toUpperCase(Locale.ROOT);
            this.samples = Math.max(0L, samples);
            this.expectancyPercent = expectancyPercent;
            this.profitFactor = Math.max(0.0, profitFactor);
            this.hasPolicy = hasPolicy;
        }

        static ExitProfile from(Properties p, String prefix, String key) {
            return new ExitProfile(
                    key,
                    p.getProperty(prefix + "exitStyle", "STATIC_DEFAULT"),
                    parseLong(p.getProperty(prefix + "samples"), 0L),
                    parseDouble(p.getProperty(prefix + "expectancyPercent"), 0.0),
                    parseDouble(p.getProperty(prefix + "profitFactor"), 0.0),
                    p.containsKey(prefix + "exitStyle")
            );
        }

        static ExitProfile none(String key) {
            return new ExitProfile(key, "STATIC_DEFAULT", 0L, 0.0, 0.0, false);
        }

        String summary() {
            return "exitStyle=" + exitStyle +
                    " samples=" + samples +
                    " expectancy=" + fmt(expectancyPercent) +
                    " profitFactor=" + fmt(profitFactor);
        }
    }

    public static final class ReadinessReview {
        public final Mode mode;
        public final String ticker;
        public final String strategy;
        public final double score;
        public final double sizingMultiplier;
        public final double expectedMoveFraction;
        public final double expectedNetEdgeFraction;
        public final String paperStatus;
        public final int paperSamples;
        public final double paperExpectancyPercent;
        public final double paperProfitFactor;
        public final String exitStyle;
        public final long exitSamples;
        public final double exitExpectancyPercent;
        public final double exitProfitFactor;
        public final String reason;

        private ReadinessReview(Mode mode,
                                StrategySignal signal,
                                double score,
                                double sizingMultiplier,
                                double expectedMoveFraction,
                                double expectedNetEdgeFraction,
                                PaperProfile paper,
                                ExitProfile exit,
                                String reason) {
            this.mode = mode == null ? Mode.BLOCK : mode;
            this.ticker = signal == null ? "UNKNOWN" : signal.getTicker();
            this.strategy = signal == null ? "UNKNOWN" : signal.getStrategyName();
            this.score = clamp(score, 0.0, 1.0);
            this.sizingMultiplier = clamp(sizingMultiplier, 0.0, 1.50);
            this.expectedMoveFraction = Math.max(0.0, expectedMoveFraction);
            this.expectedNetEdgeFraction = Math.max(0.0, expectedNetEdgeFraction);
            PaperProfile p = paper == null ? PaperProfile.unknown(this.strategy) : paper;
            ExitProfile e = exit == null ? ExitProfile.none(this.strategy) : exit;
            this.paperStatus = p.paperStatus;
            this.paperSamples = p.closedTrades;
            this.paperExpectancyPercent = p.expectancyPercent;
            this.paperProfitFactor = p.profitFactor;
            this.exitStyle = e.exitStyle;
            this.exitSamples = e.samples;
            this.exitExpectancyPercent = e.expectancyPercent;
            this.exitProfitFactor = e.profitFactor;
            this.reason = reason == null ? "" : reason;
        }

        static ReadinessReview live(Mode mode,
                                    StrategySignal signal,
                                    double score,
                                    double sizingMultiplier,
                                    double expectedMoveFraction,
                                    double expectedNetEdgeFraction,
                                    PaperProfile paper,
                                    ExitProfile exit,
                                    String reason) {
            return new ReadinessReview(mode, signal, score, sizingMultiplier, expectedMoveFraction,
                    expectedNetEdgeFraction, paper, exit, reason);
        }

        static ReadinessReview block(StrategySignal signal,
                                     double expectedMoveFraction,
                                     double expectedNetEdgeFraction,
                                     PaperProfile paper,
                                     ExitProfile exit,
                                     String reason) {
            return new ReadinessReview(Mode.BLOCK, signal, 0.0, 0.0, expectedMoveFraction,
                    expectedNetEdgeFraction, paper, exit, reason);
        }

        public boolean allowsLiveOrder() {
            return mode == Mode.LIVE_FULL || mode == Mode.LIVE_REDUCED;
        }

        public String summary() {
            return "mode=" + mode +
                    " score=" + fmt(score) +
                    " size=" + fmt(sizingMultiplier) +
                    " netEdge=" + fmt(expectedNetEdgeFraction) +
                    " paperStatus=" + paperStatus +
                    " paperSamples=" + paperSamples +
                    " exitStyle=" + exitStyle +
                    " reason=" + reason;
        }
    }

    public static final class Result {
        public final int paperStrategies;
        public final int paperPassed;
        public final int paperBlocked;
        public final int paperInsufficient;
        public final int paperUnknown;
        public final int exitProfiles;
        public final int exitPositive;
        public final int exitWeak;
        public final Path healthPath;
        public final Path reportPath;
        public final Path journalPath;

        Result(int paperStrategies,
               int paperPassed,
               int paperBlocked,
               int paperInsufficient,
               int paperUnknown,
               int exitProfiles,
               int exitPositive,
               int exitWeak,
               Path healthPath,
               Path reportPath,
               Path journalPath) {
            this.paperStrategies = paperStrategies;
            this.paperPassed = paperPassed;
            this.paperBlocked = paperBlocked;
            this.paperInsufficient = paperInsufficient;
            this.paperUnknown = paperUnknown;
            this.exitProfiles = exitProfiles;
            this.exitPositive = exitPositive;
            this.exitWeak = exitWeak;
            this.healthPath = healthPath;
            this.reportPath = reportPath;
            this.journalPath = journalPath;
        }

        public String summary() {
            return "paperStrategies=" + paperStrategies +
                    " paperPassed=" + paperPassed +
                    " paperBlocked=" + paperBlocked +
                    " paperInsufficient=" + paperInsufficient +
                    " exitPositive=" + exitPositive +
                    " exitWeak=" + exitWeak +
                    " healthPath=" + healthPath;
        }
    }
}
