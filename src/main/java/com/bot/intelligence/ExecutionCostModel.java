package com.bot.intelligence;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime view of learned execution drag. It turns fills, misses, latency, and
 * slippage into a net-edge check that scalping signals must clear.
 */
public final class ExecutionCostModel {
    private static final ExecutionCostModel INSTANCE = new ExecutionCostModel();

    private final Path policyPath = Path.of(System.getenv().getOrDefault(
            "EXECUTION_COST_POLICY_PATH", "logs/execution_cost_policy.properties"));
    private final ConcurrentHashMap<String, CostProfile> strategyProfiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CostProfile> tickerProfiles = new ConcurrentHashMap<>();
    private volatile CostProfile globalProfile = CostProfile.fallback("GLOBAL");
    private volatile long lastLoadAt = 0L;

    private ExecutionCostModel() {
    }

    public static ExecutionCostModel getInstance() {
        return INSTANCE;
    }

    public CostReview review(StrategySignal signal, StrategyContext context) {
        if (signal == null) {
            return CostReview.allow("UNKNOWN", "UNKNOWN", 0.0, 0.0, 1.0, 1.0, 0.0, 0.0,
                    "no signal");
        }
        String ticker = context != null && context.getTicker() != null && !context.getTicker().isBlank()
                ? context.getTicker()
                : signal.getTicker();
        return review(ticker, signal.getStrategyName(), normalizeExpectedMove(signal.getExpectedMovePercent()));
    }

    public CostReview review(String ticker, String strategy, double expectedMoveFraction) {
        if (!envBoolean("EXECUTION_COST_MODEL_ENABLED", true)) {
            return CostReview.allow(normalizeTicker(ticker), normalizeStrategy(strategy), normalizeExpectedMove(expectedMoveFraction),
                    0.0, 1.0, 1.0, 1.0, 0.0, "execution cost model disabled");
        }
        reloadIfNeeded();
        String normalizedTicker = normalizeTicker(ticker);
        String normalizedStrategy = normalizeStrategy(strategy);
        CostProfile strategyProfile = strategyProfiles.get(normalizedStrategy);
        CostProfile tickerProfile = tickerProfiles.get(normalizedTicker);
        CostProfile combined = combine(strategyProfile, tickerProfile, globalProfile);
        double expected = normalizeExpectedMove(expectedMoveFraction);
        double netExpected = Math.max(0.0, expected - combined.roundTripCostFraction);
        double multiplier = combined.sizingMultiplier;
        boolean blocked = combined.blocked
                && expected < combined.roundTripCostFraction * envDouble("EXECUTION_COST_BLOCK_REQUIRED_EDGE_MULTIPLE", 1.75);
        boolean tooSmallAfterCosts = expected > 0.0
                && netExpected < envDouble("EXECUTION_COST_MIN_NET_EXPECTED_MOVE_FRACTION", 0.0015)
                && expected < combined.roundTripCostFraction * envDouble("EXECUTION_COST_MIN_EDGE_MULTIPLE", 1.25);
        if (blocked || tooSmallAfterCosts) {
            return CostReview.block(normalizedTicker, normalizedStrategy, expected, combined.roundTripCostFraction,
                    netExpected, combined.fillRate, combined.avgLatencyMs,
                    "execution_cost_veto decision=" + combined.decision +
                            " costFraction=" + fmt(combined.roundTripCostFraction) +
                            " expected=" + fmt(expected) +
                            " net=" + fmt(netExpected) +
                            " fillRate=" + fmt(combined.fillRate) +
                            " avgLatencyMs=" + fmt(combined.avgLatencyMs));
        }
        return CostReview.allow(normalizedTicker, normalizedStrategy, expected, combined.roundTripCostFraction,
                Math.max(0.10, Math.min(1.20, multiplier)),
                Math.max(0.10, Math.min(1.20, combined.priorityMultiplier)),
                combined.fillRate,
                combined.avgLatencyMs,
                "execution_cost_ok decision=" + combined.decision +
                        " costFraction=" + fmt(combined.roundTripCostFraction) +
                        " expected=" + fmt(expected) +
                        " net=" + fmt(netExpected) +
                        " fillRate=" + fmt(combined.fillRate) +
                        " avgLatencyMs=" + fmt(combined.avgLatencyMs));
    }

    public double estimatedRoundTripCostFraction(String ticker, String strategy) {
        reloadIfNeeded();
        CostProfile combined = combine(strategyProfiles.get(normalizeStrategy(strategy)),
                tickerProfiles.get(normalizeTicker(ticker)),
                globalProfile);
        return Math.max(0.0, combined.roundTripCostFraction);
    }

    public double oneWaySlippageBps(String ticker, String strategy, double fallbackBps) {
        double learned = estimatedRoundTripCostFraction(ticker, strategy) * 10_000.0 / 2.0;
        return Math.max(Math.max(0.0, fallbackBps), learned);
    }

    private void reloadIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastLoadAt < envLong("EXECUTION_COST_POLICY_RELOAD_MS", 30_000L)) {
            return;
        }
        lastLoadAt = now;
        ConcurrentHashMap<String, CostProfile> loadedStrategies = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, CostProfile> loadedTickers = new ConcurrentHashMap<>();
        CostProfile loadedGlobal = CostProfile.fallback("GLOBAL");
        if (Files.exists(policyPath)) {
            try (InputStream in = Files.newInputStream(policyPath)) {
                Properties p = new Properties();
                p.load(in);
                for (String key : p.stringPropertyNames()) {
                    if (key.startsWith("strategy.") && key.endsWith(".roundTripCostFraction")) {
                        String strategy = normalizeStrategy(key.substring("strategy.".length(),
                                key.length() - ".roundTripCostFraction".length()));
                        loadedStrategies.put(strategy, profile(p, "strategy." + strategy + ".", strategy));
                    } else if (key.startsWith("ticker.") && key.endsWith(".roundTripCostFraction")) {
                        String ticker = normalizeTicker(key.substring("ticker.".length(),
                                key.length() - ".roundTripCostFraction".length()));
                        loadedTickers.put(ticker, profile(p, "ticker." + ticker + ".", ticker));
                    }
                }
                loadedGlobal = profile(p, "global.", "GLOBAL");
            } catch (Exception e) {
                System.out.println("EXECUTION COST POLICY LOAD FAILED: " + policyPath + " " + e.getMessage());
            }
        }
        strategyProfiles.clear();
        strategyProfiles.putAll(loadedStrategies);
        tickerProfiles.clear();
        tickerProfiles.putAll(loadedTickers);
        globalProfile = loadedGlobal;
    }

    private static CostProfile profile(Properties p, String prefix, String key) {
        return new CostProfile(
                key,
                p.getProperty(prefix + "decision", "ALLOW"),
                parseDouble(p.getProperty(prefix + "roundTripCostFraction"), fallbackRoundTripCostFraction()),
                parseDouble(p.getProperty(prefix + "fillRate"), 1.0),
                parseDouble(p.getProperty(prefix + "avgLatencyMs"), 0.0),
                parseDouble(p.getProperty(prefix + "avgAbsSlippagePercent"), 0.0),
                parseDouble(p.getProperty(prefix + "avgSizeFillRatio"), 1.0),
                parseDouble(p.getProperty(prefix + "sizingMultiplier"), 1.0),
                parseDouble(p.getProperty(prefix + "priorityMultiplier"), 1.0),
                Boolean.parseBoolean(p.getProperty(prefix + "blocked", "false"))
        );
    }

    private static CostProfile combine(CostProfile strategy, CostProfile ticker, CostProfile global) {
        CostProfile fallback = global == null ? CostProfile.fallback("GLOBAL") : global;
        double strategyWeight = strategy == null ? 0.0 : 0.60;
        double tickerWeight = ticker == null ? 0.0 : 0.30;
        double globalWeight = 1.0 - strategyWeight - tickerWeight;
        if (globalWeight < 0.10) {
            globalWeight = 0.10;
            double scale = 0.90 / Math.max(0.0001, strategyWeight + tickerWeight);
            strategyWeight *= scale;
            tickerWeight *= scale;
        }
        double totalWeight = strategyWeight + tickerWeight + globalWeight;
        strategyWeight /= totalWeight;
        tickerWeight /= totalWeight;
        globalWeight /= totalWeight;
        double cost = weighted(strategy, ticker, fallback, strategyWeight, tickerWeight, globalWeight,
                Value.ROUND_TRIP_COST);
        double fillRate = weighted(strategy, ticker, fallback, strategyWeight, tickerWeight, globalWeight,
                Value.FILL_RATE);
        double latency = weighted(strategy, ticker, fallback, strategyWeight, tickerWeight, globalWeight,
                Value.LATENCY);
        double slip = weighted(strategy, ticker, fallback, strategyWeight, tickerWeight, globalWeight,
                Value.SLIPPAGE);
        double sizeRatio = weighted(strategy, ticker, fallback, strategyWeight, tickerWeight, globalWeight,
                Value.SIZE_RATIO);
        double sizing = weighted(strategy, ticker, fallback, strategyWeight, tickerWeight, globalWeight,
                Value.SIZING_MULTIPLIER);
        double priority = weighted(strategy, ticker, fallback, strategyWeight, tickerWeight, globalWeight,
                Value.PRIORITY_MULTIPLIER);
        boolean blocked = (strategy != null && strategy.blocked) || (ticker != null && ticker.blocked) || fallback.blocked;
        String decision = blocked ? "BLOCK_COST" : (sizing < 0.95 || priority < 0.95 ? "SHRINK_COST" : "ALLOW");
        return new CostProfile("COMBINED", decision, cost, fillRate, latency, slip, sizeRatio, sizing, priority, blocked);
    }

    private enum Value { ROUND_TRIP_COST, FILL_RATE, LATENCY, SLIPPAGE, SIZE_RATIO, SIZING_MULTIPLIER, PRIORITY_MULTIPLIER }

    private static double weighted(CostProfile strategy,
                                   CostProfile ticker,
                                   CostProfile global,
                                   double strategyWeight,
                                   double tickerWeight,
                                   double globalWeight,
                                   Value value) {
        return value(strategy, value) * strategyWeight
                + value(ticker, value) * tickerWeight
                + value(global, value) * globalWeight;
    }

    private static double value(CostProfile p, Value value) {
        CostProfile profile = p == null ? CostProfile.fallback("GLOBAL") : p;
        return switch (value) {
            case ROUND_TRIP_COST -> profile.roundTripCostFraction;
            case FILL_RATE -> profile.fillRate;
            case LATENCY -> profile.avgLatencyMs;
            case SLIPPAGE -> profile.avgAbsSlippagePercent;
            case SIZE_RATIO -> profile.avgSizeFillRatio;
            case SIZING_MULTIPLIER -> profile.sizingMultiplier;
            case PRIORITY_MULTIPLIER -> profile.priorityMultiplier;
        };
    }

    private static double normalizeExpectedMove(double value) {
        double expected = Math.abs(Double.isFinite(value) ? value : 0.0);
        return expected > 1.0 ? expected / 100.0 : expected;
    }

    private static double fallbackRoundTripCostFraction() {
        return Math.max(0.0, envDouble("EXECUTION_COST_FALLBACK_ROUND_TRIP_BPS", 12.0) / 10_000.0);
    }

    private static String normalizeStrategy(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String normalizeTicker(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()) || "yes".equalsIgnoreCase(value.trim());
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
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

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.6f", Double.isFinite(value) ? value : 0.0);
    }

    private static final class CostProfile {
        final String key;
        final String decision;
        final double roundTripCostFraction;
        final double fillRate;
        final double avgLatencyMs;
        final double avgAbsSlippagePercent;
        final double avgSizeFillRatio;
        final double sizingMultiplier;
        final double priorityMultiplier;
        final boolean blocked;

        CostProfile(String key,
                    String decision,
                    double roundTripCostFraction,
                    double fillRate,
                    double avgLatencyMs,
                    double avgAbsSlippagePercent,
                    double avgSizeFillRatio,
                    double sizingMultiplier,
                    double priorityMultiplier,
                    boolean blocked) {
            this.key = key;
            this.decision = decision == null ? "ALLOW" : decision;
            this.roundTripCostFraction = Math.max(0.0, roundTripCostFraction);
            this.fillRate = Math.max(0.0, Math.min(1.0, fillRate));
            this.avgLatencyMs = Math.max(0.0, avgLatencyMs);
            this.avgAbsSlippagePercent = Math.max(0.0, avgAbsSlippagePercent);
            this.avgSizeFillRatio = Math.max(0.0, Math.min(1.0, avgSizeFillRatio));
            this.sizingMultiplier = Math.max(0.0, Math.min(1.50, sizingMultiplier));
            this.priorityMultiplier = Math.max(0.0, Math.min(1.50, priorityMultiplier));
            this.blocked = blocked;
        }

        static CostProfile fallback(String key) {
            return new CostProfile(key, "ALLOW", fallbackRoundTripCostFraction(), 1.0, 0.0, 0.0,
                    1.0, 1.0, 1.0, false);
        }
    }

    public static final class CostReview {
        public final boolean approved;
        public final String ticker;
        public final String strategy;
        public final double expectedMoveFraction;
        public final double roundTripCostFraction;
        public final double expectedNetMoveFraction;
        public final double sizingMultiplier;
        public final double priorityMultiplier;
        public final double fillRate;
        public final double avgLatencyMs;
        public final String reason;

        private CostReview(boolean approved,
                           String ticker,
                           String strategy,
                           double expectedMoveFraction,
                           double roundTripCostFraction,
                           double sizingMultiplier,
                           double priorityMultiplier,
                           double fillRate,
                           double avgLatencyMs,
                           String reason) {
            this.approved = approved;
            this.ticker = ticker;
            this.strategy = strategy;
            this.expectedMoveFraction = Math.max(0.0, expectedMoveFraction);
            this.roundTripCostFraction = Math.max(0.0, roundTripCostFraction);
            this.expectedNetMoveFraction = Math.max(0.0, this.expectedMoveFraction - this.roundTripCostFraction);
            this.sizingMultiplier = Math.max(0.0, Math.min(1.50, sizingMultiplier));
            this.priorityMultiplier = Math.max(0.0, Math.min(1.50, priorityMultiplier));
            this.fillRate = Math.max(0.0, Math.min(1.0, fillRate));
            this.avgLatencyMs = Math.max(0.0, avgLatencyMs);
            this.reason = reason == null ? "" : reason;
        }

        static CostReview allow(String ticker,
                                String strategy,
                                double expectedMoveFraction,
                                double roundTripCostFraction,
                                double sizingMultiplier,
                                double priorityMultiplier,
                                double fillRate,
                                double avgLatencyMs,
                                String reason) {
            return new CostReview(true, ticker, strategy, expectedMoveFraction, roundTripCostFraction,
                    sizingMultiplier, priorityMultiplier, fillRate, avgLatencyMs, reason);
        }

        static CostReview block(String ticker,
                                String strategy,
                                double expectedMoveFraction,
                                double roundTripCostFraction,
                                double netExpectedMoveFraction,
                                double fillRate,
                                double avgLatencyMs,
                                String reason) {
            return new CostReview(false, ticker, strategy, expectedMoveFraction, roundTripCostFraction,
                    0.0, 0.0, fillRate, avgLatencyMs,
                    reason + " netExpectedMoveFraction=" + fmt(netExpectedMoveFraction));
        }
    }
}
