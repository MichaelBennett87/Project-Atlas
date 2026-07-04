package com.bot.intelligence;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Regime-aware strategy gate. It does not replace the strategy engine; it keeps
 * strategies from competing in market regimes where their historical structure
 * is least appropriate.
 */
public final class StrategySelectionGovernor {
    private static final StrategySelectionGovernor INSTANCE = new StrategySelectionGovernor();
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    private volatile long lastLogAt = 0L;
    private volatile long lastReplayPolicyLoadAt = 0L;
    private volatile long lastRegimePolicyLoadAt = 0L;
    private final Map<String, String> replayDisabledStrategies = new ConcurrentHashMap<>();
    private final Map<String, String> regimeDisabledStrategies = new ConcurrentHashMap<>();
    private final Map<String, Double> regimeStrategyMultipliers = new ConcurrentHashMap<>();
    private final Map<String, String> regimeStrategyDecisions = new ConcurrentHashMap<>();
    private final Map<String, String> regimeStrategyReasons = new ConcurrentHashMap<>();

    private StrategySelectionGovernor() {
    }

    public static StrategySelectionGovernor getInstance() {
        return INSTANCE;
    }

    public boolean isStrategyEnabled(String strategyName) {
        if (strategyName == null || strategyName.isBlank()) {
            return true;
        }
        reloadReplayPolicyIfNeeded();
        reloadRegimeStrategyPolicyIfNeeded();
        String normalizedStrategy = normalizeStrategy(strategyName);
        if (replayDisabledStrategies.containsKey(normalizedStrategy)) {
            return false;
        }
        if (regimeDisabledReasonFor(normalizedStrategy) != null) {
            return false;
        }
        if (!envBoolean("REGIME_AWARE_STRATEGY_SELECTION_ENABLED", true)) {
            return true;
        }
        MarketRegime regime = MarketRegimeEngine.getInstance().currentSnapshot().getRegime();
        String name = strategyName.toLowerCase(Locale.ROOT);

        switch (regime) {
            case STRONG_UPTREND:
            case UPTREND:
                return !containsAny(name, "breakdown", "exhaustion short", "green-to-red", "offering fade");
            case DOWNTREND:
                return !containsAny(name, "dip buy", "red-to-green", "high-tight", "pullback continuation");
            case PANIC:
                return containsAny(name, "panic", "vwap", "reversal", "breakdown", "short", "liquidity") || containsAny(name, "market_intelligence");
            case HIGH_VOLATILITY:
                return !containsAny(name, "range bound mean reversion");
            case RANGE_BOUND:
                return !containsAny(name, "parabolic", "high-tight flag");
            default:
                return true;
        }
    }

    public String disabledReason(String strategyName) {
        reloadReplayPolicyIfNeeded();
        reloadRegimeStrategyPolicyIfNeeded();
        String replayReason = replayDisabledStrategies.get(normalizeStrategy(strategyName));
        if (replayReason != null) {
            return "Disabled by offline strategy governor: strategy=" + strategyName + " reason=" + replayReason;
        }
        String regimeReason = regimeDisabledReasonFor(normalizeStrategy(strategyName));
        if (regimeReason != null) {
            return "Disabled by learned regime strategy policy: strategy=" + strategyName + " session=" + currentSessionRegimeKey() + " reason=" + regimeReason;
        }
        MarketRegimeSnapshot snapshot = MarketRegimeEngine.getInstance().currentSnapshot();
        return "Disabled by regime-aware strategy selection: regime=" + snapshot.getRegime() + " reason=" + snapshot.getReason() + " strategy=" + strategyName;
    }

    public double regimeMultiplier(String strategyName) {
        RegimeSizingReview review = regimeSizingReview(strategyName);
        if (review.disabled) {
            return envDouble("REGIME_DISABLED_SIGNAL_MULTIPLIER", 0.10);
        }
        return review.sizingMultiplier;
    }

    public RegimeSizingReview regimeSizingReview(String strategyName) {
        if (strategyName == null || strategyName.isBlank() || !envBoolean("REGIME_STRATEGY_MULTIPLIER_ENABLED", true)) {
            return RegimeSizingReview.neutral(normalizeStrategy(strategyName), currentSessionRegimeKey(),
                    "learned regime sizing disabled or strategy missing");
        }
        reloadRegimeStrategyPolicyIfNeeded();
        String normalizedStrategy = normalizeStrategy(strategyName);
        String sessionRegime = currentSessionRegimeKey();
        RegimeSizingReview sessionReview = reviewFor(normalizedStrategy, sessionRegime);
        if (sessionReview.hasPolicy || sessionReview.disabled) {
            return sessionReview;
        }
        String marketRegime = currentMarketRegimeKey();
        RegimeSizingReview marketReview = reviewFor(normalizedStrategy, marketRegime);
        if (marketReview.hasPolicy || marketReview.disabled) {
            return marketReview;
        }
        return RegimeSizingReview.neutral(normalizedStrategy, sessionRegime,
                "no learned regime sizing policy for current session");
    }

    public String regimeSizingDescription(String strategyName) {
        return regimeSizingReview(strategyName).summary();
    }

    public void maybeLog() {
        long now = System.currentTimeMillis();
        if (now - lastLogAt < envLong("STRATEGY_GOVERNOR_LOG_INTERVAL_MS", 180_000L)) {
            return;
        }
        lastLogAt = now;
        MarketRegimeSnapshot snapshot = MarketRegimeEngine.getInstance().currentSnapshot();
        reloadReplayPolicyIfNeeded();
        reloadRegimeStrategyPolicyIfNeeded();
        System.out.println("STRATEGY SELECTION GOVERNOR: active=true regime=" + snapshot.getRegime() +
                " reason=" + snapshot.getReason() +
                " session=" + currentSessionRegimeKey() +
                " replayDisabled=" + replayDisabledStrategies.keySet() +
                " learnedRegimeDisabled=" + regimeDisabledStrategies.keySet());
    }

    private void reloadReplayPolicyIfNeeded() {
        if (!envBoolean("REPLAY_STRATEGY_GOVERNOR_ENABLED", true)) {
            replayDisabledStrategies.clear();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReplayPolicyLoadAt < envLong("REPLAY_STRATEGY_GOVERNOR_RELOAD_MS", 30_000L)) {
            return;
        }
        lastReplayPolicyLoadAt = now;
        Map<String, String> loaded = new ConcurrentHashMap<>();
        loadDisabledStrategies(
                loaded,
                Path.of(System.getenv().getOrDefault("REPLAY_STRATEGY_POLICY_PATH", "logs/replay_strategy_policy.properties")),
                "replay-driven optimizer"
        );
        loadDisabledStrategies(
                loaded,
                Path.of(System.getenv().getOrDefault("SIMULATION_STRATEGY_POLICY_PATH", "logs/simulation_strategy_policy.properties")),
                "strategy simulation promotion engine"
        );
        loadDisabledStrategies(
                loaded,
                Path.of(System.getenv().getOrDefault("BAR_BY_BAR_SIMULATION_POLICY_PATH", "logs/bar_by_bar_simulation_policy.properties")),
                "bar-by-bar historical simulator"
        );
        loadDisabledStrategies(
                loaded,
                Path.of(System.getenv().getOrDefault("PAPER_TRADING_STRATEGY_POLICY_PATH", "logs/paper_trading_strategy_policy.properties")),
                "paper trading performance gate"
        );
        replayDisabledStrategies.clear();
        replayDisabledStrategies.putAll(loaded);
    }

    private void reloadRegimeStrategyPolicyIfNeeded() {
        if (!envBoolean("REGIME_STRATEGY_POLICY_ENABLED", true)) {
            regimeDisabledStrategies.clear();
            regimeStrategyMultipliers.clear();
            regimeStrategyDecisions.clear();
            regimeStrategyReasons.clear();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRegimePolicyLoadAt < envLong("REGIME_STRATEGY_POLICY_RELOAD_MS", 30_000L)) {
            return;
        }
        lastRegimePolicyLoadAt = now;
        Path path = Path.of(System.getenv().getOrDefault("REGIME_STRATEGY_POLICY_PATH", "logs/regime_strategy_policy.properties"));
        Map<String, String> disabled = new ConcurrentHashMap<>();
        Map<String, Double> multipliers = new ConcurrentHashMap<>();
        Map<String, String> decisions = new ConcurrentHashMap<>();
        Map<String, String> reasons = new ConcurrentHashMap<>();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                Properties p = new Properties();
                p.load(in);
                for (String key : p.stringPropertyNames()) {
                    if (key.startsWith("regimeDisabledStrategy.")) {
                        StrategyRegimeKey parsed = parseStrategyRegimeKey(key, "regimeDisabledStrategy.");
                        if (parsed != null && Boolean.parseBoolean(p.getProperty(key, "false"))) {
                            String reason = p.getProperty(
                                    "strategy." + parsed.strategy + ".regime." + parsed.regime + ".reason",
                                    "disabled by regime-tagged strategy performance");
                            String pKey = policyKey(parsed.strategy, parsed.regime);
                            disabled.put(pKey, reason);
                            reasons.put(pKey, reason);
                            decisions.put(pKey, "DISABLE_REGIME");
                        }
                    } else if (key.startsWith("strategyMultiplier.")) {
                        StrategyRegimeKey parsed = parseStrategyRegimeKey(key, "strategyMultiplier.");
                        if (parsed != null) {
                            String pKey = policyKey(parsed.strategy, parsed.regime);
                            multipliers.put(pKey, parseDouble(p.getProperty(key), 1.0));
                            decisions.put(pKey, p.getProperty("regimeDecision." + parsed.strategy + "." + parsed.regime, "WATCH_REGIME"));
                            reasons.put(pKey, p.getProperty(
                                    "strategy." + parsed.strategy + ".regime." + parsed.regime + ".reason",
                                    "learned regime-tagged strategy sizing"));
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("STRATEGY SELECTION GOVERNOR REGIME POLICY LOAD FAILED: " + path + " " + e.getMessage());
            }
        }
        regimeDisabledStrategies.clear();
        regimeDisabledStrategies.putAll(disabled);
        regimeStrategyMultipliers.clear();
        regimeStrategyMultipliers.putAll(multipliers);
        regimeStrategyDecisions.clear();
        regimeStrategyDecisions.putAll(decisions);
        regimeStrategyReasons.clear();
        regimeStrategyReasons.putAll(reasons);
    }

    private void loadDisabledStrategies(Map<String, String> loaded, Path path, String source) {
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                Properties p = new Properties();
                p.load(in);
                for (String key : p.stringPropertyNames()) {
                    if (!key.startsWith("disabledStrategy.")) {
                        continue;
                    }
                    String strategy = normalizeStrategy(key.substring("disabledStrategy.".length()));
                    if (strategy.isBlank()) {
                        continue;
                    }
                    boolean disabled = Boolean.parseBoolean(p.getProperty(key, "false"));
                    if (disabled) {
                        String reason = p.getProperty("strategy." + strategy + ".reason", "disabled by " + source);
                        loaded.put(strategy, source + ": " + reason);
                    }
                }
            } catch (Exception e) {
                System.out.println("STRATEGY SELECTION GOVERNOR POLICY LOAD FAILED: " + path + " " + e.getMessage());
            }
        }
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String regimeDisabledReasonFor(String normalizedStrategy) {
        String sessionReason = regimeDisabledStrategies.get(policyKey(normalizedStrategy, currentSessionRegimeKey()));
        if (sessionReason != null) {
            return sessionReason;
        }
        MarketRegimeSnapshot snapshot = MarketRegimeEngine.getInstance().currentSnapshot();
        if (snapshot != null && snapshot.getRegime() != null) {
            return regimeDisabledStrategies.get(policyKey(normalizedStrategy, "MARKET_" + snapshot.getRegime().name()));
        }
        return null;
    }

    private RegimeSizingReview reviewFor(String normalizedStrategy, String regimeKey) {
        String pKey = policyKey(normalizedStrategy, regimeKey);
        String disabledReason = regimeDisabledStrategies.get(pKey);
        Double rawMultiplier = regimeStrategyMultipliers.get(pKey);
        boolean hasPolicy = rawMultiplier != null || disabledReason != null || regimeStrategyDecisions.containsKey(pKey);
        double multiplier = rawMultiplier == null || !Double.isFinite(rawMultiplier)
                ? 1.0
                : Math.max(0.10, Math.min(2.0, rawMultiplier));
        String decision = disabledReason != null
                ? "DISABLE_REGIME"
                : regimeStrategyDecisions.getOrDefault(pKey, hasPolicy ? "WATCH_REGIME" : "NEUTRAL");
        String reason = disabledReason != null
                ? disabledReason
                : regimeStrategyReasons.getOrDefault(pKey, hasPolicy
                ? "learned regime-tagged strategy sizing"
                : "no learned regime policy");
        if (disabledReason != null) {
            multiplier = Math.min(multiplier, envDouble("REGIME_DISABLED_SIZE_MULTIPLIER", 0.0));
        }
        return new RegimeSizingReview(normalizedStrategy, regimeKey, decision, reason,
                multiplier, disabledReason != null, hasPolicy);
    }

    private static StrategyRegimeKey parseStrategyRegimeKey(String propertyKey, String prefix) {
        String rest = propertyKey.substring(prefix.length());
        int split = rest.lastIndexOf('.');
        if (split <= 0 || split >= rest.length() - 1) {
            return null;
        }
        String strategy = normalizeStrategy(rest.substring(0, split));
        String regime = normalizeRegime(rest.substring(split + 1));
        if (strategy.isBlank() || regime.isBlank()) {
            return null;
        }
        return new StrategyRegimeKey(strategy, regime);
    }

    private static String currentSessionRegimeKey() {
        LocalTime t = ZonedDateTime.now(MARKET_ZONE).toLocalTime();
        if (!t.isBefore(LocalTime.of(4, 0)) && t.isBefore(LocalTime.of(9, 30))) {
            return "SESSION_PRE_MARKET";
        }
        if (!t.isBefore(LocalTime.of(9, 30)) && t.isBefore(LocalTime.of(16, 0))) {
            return "SESSION_REGULAR";
        }
        if (!t.isBefore(LocalTime.of(16, 0)) && t.isBefore(LocalTime.of(20, 0))) {
            return "SESSION_AFTER_HOURS";
        }
        return "SESSION_CLOSED";
    }

    private static String currentMarketRegimeKey() {
        MarketRegimeSnapshot snapshot = MarketRegimeEngine.getInstance().currentSnapshot();
        if (snapshot == null || snapshot.getRegime() == null) {
            return "MARKET_UNKNOWN";
        }
        return "MARKET_" + snapshot.getRegime().name();
    }

    private static String policyKey(String strategy, String regime) {
        return normalizeStrategy(strategy) + "|" + normalizeRegime(regime);
    }

    private static String normalizeStrategy(String strategyName) {
        return strategyName == null || strategyName.isBlank()
                ? ""
                : strategyName.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String normalizeRegime(String regimeName) {
        return regimeName == null || regimeName.isBlank()
                ? ""
                : regimeName.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        return "true".equalsIgnoreCase(value.trim());
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

    private static final class StrategyRegimeKey {
        final String strategy;
        final String regime;

        StrategyRegimeKey(String strategy, String regime) {
            this.strategy = strategy;
            this.regime = regime;
        }
    }

    public static final class RegimeSizingReview {
        public final String strategy;
        public final String regimeKey;
        public final String decision;
        public final String reason;
        public final double sizingMultiplier;
        public final boolean disabled;
        public final boolean hasPolicy;

        private RegimeSizingReview(String strategy,
                                   String regimeKey,
                                   String decision,
                                   String reason,
                                   double sizingMultiplier,
                                   boolean disabled,
                                   boolean hasPolicy) {
            this.strategy = strategy == null ? "" : strategy;
            this.regimeKey = regimeKey == null ? "" : regimeKey;
            this.decision = decision == null ? "NEUTRAL" : decision;
            this.reason = reason == null ? "" : reason;
            this.sizingMultiplier = Math.max(0.0, Math.min(2.0, sizingMultiplier));
            this.disabled = disabled;
            this.hasPolicy = hasPolicy;
        }

        static RegimeSizingReview neutral(String strategy, String regimeKey, String reason) {
            return new RegimeSizingReview(strategy, regimeKey, "NEUTRAL", reason, 1.0, false, false);
        }

        public String summary() {
            return "learnedRegimeSizing strategy=" + strategy +
                    " regime=" + regimeKey +
                    " decision=" + decision +
                    " multiplier=" + String.format(Locale.ROOT, "%.3f", sizingMultiplier) +
                    " disabled=" + disabled +
                    " reason=" + reason;
        }
    }
}
