package com.bot.intelligence;

import com.bot.master.MasterStrategyDecision;
import com.bot.master.StrategyAction;
import com.bot.master.StrategyContext;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Properties;

/**
 * Tracks explicit learning/policy versions so overnight optimization can compare
 * policies instead of blindly drifting thresholds from small samples.
 */
public final class PolicyVersionManager {
    private static final PolicyVersionManager INSTANCE = new PolicyVersionManager();

    private final boolean enabled = envBoolean("POLICY_VERSION_MANAGER_ENABLED", true);
    private final Path statePath = Paths.get(System.getenv().getOrDefault("POLICY_VERSION_STATE", "logs/policy_version_state.properties"));
    private final Path journalPath = Paths.get(System.getenv().getOrDefault("POLICY_VERSION_JOURNAL", "logs/policy_version_journal.csv"));
    private final String version;
    private int decisions;
    private int buys;
    private int holds;

    private PolicyVersionManager() {
        this.version = loadOrCreateVersion();
        if (enabled) {
            System.out.println("POLICY VERSION MANAGER READY: version=" + version + " state=" + statePath + " journal=" + journalPath);
        }
    }

    public static PolicyVersionManager getInstance() { return INSTANCE; }

    public String currentVersion() { return version; }

    public synchronized void recordDecision(StrategyContext context, MasterStrategyDecision decision, String actionLabel) {
        if (!enabled || decision == null) return;
        decisions++;
        if (decision.getAction() == StrategyAction.BUY) buys++; else holds++;
        try {
            append(context, decision, actionLabel);
            writeState();
        } catch (Exception e) {
            if (envBoolean("POLICY_VERSION_VERBOSE_ERRORS", false)) {
                System.out.println("POLICY VERSION RECORD FAILED: " + e.getMessage());
            }
        }
    }

    public synchronized boolean shouldPromoteCandidate(
            double candidateProfitFactor,
            double currentProfitFactor,
            double candidateMaxDrawdown,
            int candidateTrades
    ) {
        int minTrades = envInt("POLICY_PROMOTION_MIN_TRADES", 25);
        double minProfitFactorEdge = envDouble("POLICY_PROMOTION_MIN_PROFIT_FACTOR_EDGE", 0.10);
        double maxDrawdown = envDouble("POLICY_PROMOTION_MAX_DRAWDOWN", 0.08);
        if (candidateTrades < minTrades) return false;
        if (candidateMaxDrawdown > maxDrawdown) return false;
        return candidateProfitFactor >= currentProfitFactor + minProfitFactorEdge;
    }

    private String loadOrCreateVersion() {
        try {
            Properties p = new Properties();
            if (Files.exists(statePath)) {
                try (java.io.InputStream in = Files.newInputStream(statePath)) {
                    p.load(in);
                }
                String existing = p.getProperty("currentVersion");
                if (existing != null && !existing.isBlank()) return existing.trim();
            }
        } catch (Exception ignored) {
        }
        String date = LocalDate.now(ZoneId.of(System.getenv().getOrDefault("POLICY_VERSION_ZONE", "America/New_York"))).toString().replace("-", "");
        return "policy-" + date + "-v1";
    }

    private void append(StrategyContext context, MasterStrategyDecision decision, String actionLabel) throws IOException {
        Path parent = journalPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (BufferedWriter writer = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(String.join(",",
                    clean(Instant.now().toString()),
                    clean(version),
                    clean(actionLabel),
                    clean(context == null ? "" : context.getTicker()),
                    clean(decision.getTicker()),
                    clean(decision.getAction().name()),
                    clean(decision.getWinningSignal() == null ? "" : decision.getWinningSignal().getStrategyName()),
                    decision.getWinningSignal() == null ? "" : fmt(decision.getWinningSignal().getConfidence()),
                    decision.getWinningSignal() == null ? "" : fmt(decision.getWinningSignal().priorityScore()),
                    Integer.toString(decision.getAllSignals() == null ? 0 : decision.getAllSignals().size()),
                    clean(decision.getReason())
            ));
            writer.newLine();
        }
    }

    private void writeState() throws IOException {
        Path parent = statePath.getParent();
        if (parent != null) Files.createDirectories(parent);
        Properties p = new Properties();
        p.setProperty("currentVersion", version);
        p.setProperty("updatedAt", Instant.now().toString());
        p.setProperty("decisions", Integer.toString(decisions));
        p.setProperty("buyDecisions", Integer.toString(buys));
        p.setProperty("holdDecisions", Integer.toString(holds));
        p.setProperty("promotionRule", "candidateTrades>=POLICY_PROMOTION_MIN_TRADES and profitFactor edge plus drawdown gate");
        try (java.io.OutputStream out = Files.newOutputStream(statePath)) {
            p.store(out, "Policy version state");
        }
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()) || "yes".equalsIgnoreCase(value.trim());
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String fmt(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) value = 0.0;
        return String.format(Locale.ROOT, "%.5f", value);
    }
}
