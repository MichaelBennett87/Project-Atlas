package com.bot.intelligence;

import com.bot.model.Position;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime reader for the exit shadow tournament policy.
 */
public final class AdaptiveExitPolicyModel {
    private static final AdaptiveExitPolicyModel INSTANCE = new AdaptiveExitPolicyModel();

    private final Path policyPath = Path.of(env("EXIT_SHADOW_TOURNAMENT_POLICY_PATH",
            "logs/exit_shadow_tournament_policy.properties"));
    private final Map<String, ExitProfile> strategyProfiles = new ConcurrentHashMap<>();
    private volatile ExitProfile globalProfile = null;
    private volatile long lastLoadAtMs = 0L;

    private AdaptiveExitPolicyModel() {
    }

    public static AdaptiveExitPolicyModel getInstance() {
        return INSTANCE;
    }

    public ExitPlan plan(Position position,
                         boolean shortPosition,
                         double fallbackPartialTargetPercent,
                         double fallbackPartialFraction,
                         double fallbackTrailingGivebackPercent,
                         double fallbackFullProfitLockPercent,
                         double fallbackHardStopLossPercent,
                         long fallbackMaxHoldMs) {
        double safeFallbackHardStop = Math.max(0.0005, Math.abs(fallbackHardStopLossPercent));
        ExitPlan fallback = new ExitPlan(
                "STATIC_DEFAULT",
                clamp(fallbackPartialTargetPercent, 0.0005, 0.50),
                clamp(fallbackPartialFraction, 0.0, 0.95),
                clamp(fallbackTrailingGivebackPercent, 0.0005, 0.50),
                clamp(fallbackFullProfitLockPercent, 0.0, 1.00),
                safeFallbackHardStop,
                Math.max(0L, fallbackMaxHoldMs),
                "static defaults"
        );
        if (!envBool("ADAPTIVE_EXIT_POLICY_ENABLED", true)) {
            return fallback;
        }
        reloadIfNeeded();
        String strategy = normalizeStrategy(position == null ? "" : position.strategyName);
        ExitProfile strategyProfile = strategyProfiles.get(strategy);
        if (strategyProfile != null) {
            return profilePassesQualityGate(strategyProfile) ? planFromProfile(
                    strategyProfile,
                    shortPosition,
                    fallback,
                    safeFallbackHardStop,
                    fallbackFullProfitLockPercent,
                    fallbackMaxHoldMs
            ) : fallback;
        }
        if (globalProfile == null || !profilePassesQualityGate(globalProfile)) {
            return fallback;
        }
        return planFromProfile(
                globalProfile,
                shortPosition,
                fallback,
                safeFallbackHardStop,
                fallbackFullProfitLockPercent,
                fallbackMaxHoldMs
        );
    }

    private ExitPlan planFromProfile(ExitProfile profile,
                                     boolean shortPosition,
                                     ExitPlan fallback,
                                     double safeFallbackHardStop,
                                     double fallbackFullProfitLockPercent,
                                     long fallbackMaxHoldMs) {
        double partialTarget = clamp(profile.partialProfitTargetPercent, 0.0005, 0.50);
        double partialFraction = clamp(profile.partialExitFraction, 0.0, 0.95);
        double trailingGiveback = clamp(profile.trailingGivebackPercent, 0.0005, 0.50);
        double fullProfitLock = clamp(profile.fullProfitLockPercent, 0.0, 1.00);
        double hardStop = profile.hardStopLossPercent > 0.0
                ? Math.min(safeFallbackHardStop, profile.hardStopLossPercent)
                : safeFallbackHardStop;
        long maxHoldMs = profile.maxHoldMs > 0L && fallbackMaxHoldMs > 0L
                ? Math.min(fallbackMaxHoldMs, profile.maxHoldMs)
                : fallbackMaxHoldMs;

        if (shortPosition && fullProfitLock <= 0.0) {
            fullProfitLock = Math.max(partialTarget * 2.0, fallbackFullProfitLockPercent);
        }
        return new ExitPlan(
                profile.exitStyle,
                partialTarget,
                partialFraction,
                trailingGiveback,
                fullProfitLock,
                hardStop,
                maxHoldMs,
                profile.reason
        );
    }

    private static boolean profilePassesQualityGate(ExitProfile profile) {
        if (profile == null) {
            return false;
        }
        long minSamples = Math.max(1L, envLong("ADAPTIVE_EXIT_POLICY_MIN_SAMPLES", 8L));
        double minExpectancy = envDouble("ADAPTIVE_EXIT_POLICY_MIN_EXPECTANCY", 0.0);
        double minProfitFactor = envDouble("ADAPTIVE_EXIT_POLICY_MIN_PROFIT_FACTOR", 1.0);
        return profile.samples >= minSamples
                && profile.expectancyPercent > minExpectancy
                && profile.profitFactor >= minProfitFactor;
    }

    private void reloadIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastLoadAtMs < Math.max(1_000L, envLong("ADAPTIVE_EXIT_POLICY_RELOAD_MS", 30_000L))) {
            return;
        }
        lastLoadAtMs = now;
        Map<String, ExitProfile> loadedStrategies = new ConcurrentHashMap<>();
        ExitProfile loadedGlobal = null;
        if (Files.exists(policyPath)) {
            try (InputStream in = Files.newInputStream(policyPath)) {
                Properties p = new Properties();
                p.load(in);
                loadedGlobal = profile(p, "global.", "GLOBAL");
                for (String key : p.stringPropertyNames()) {
                    String prefix = "strategy.";
                    String suffix = ".exitStyle";
                    if (!key.startsWith(prefix) || !key.endsWith(suffix)) {
                        continue;
                    }
                    String strategy = normalizeStrategy(key.substring(prefix.length(), key.length() - suffix.length()));
                    if (!strategy.isBlank()) {
                        loadedStrategies.put(strategy, profile(p, "strategy." + strategy + ".", strategy));
                    }
                }
            } catch (Exception e) {
                System.out.println("ADAPTIVE EXIT POLICY LOAD FAILED: " + policyPath + " " + e.getMessage());
            }
        }
        strategyProfiles.clear();
        strategyProfiles.putAll(loadedStrategies);
        globalProfile = loadedGlobal;
    }

    private static ExitProfile profile(Properties p, String prefix, String key) {
        return new ExitProfile(
                key,
                p.getProperty(prefix + "exitStyle", "STATIC_DEFAULT"),
                parseDouble(p.getProperty(prefix + "partialProfitTargetPercent"), 0.0),
                parseDouble(p.getProperty(prefix + "partialExitFraction"), 0.50),
                parseDouble(p.getProperty(prefix + "trailingGivebackPercent"), 0.0),
                parseDouble(p.getProperty(prefix + "fullProfitLockPercent"), 0.0),
                parseDouble(p.getProperty(prefix + "hardStopLossPercent"), 0.0),
                parseLong(p.getProperty(prefix + "maxHoldMs"), 0L),
                parseLong(p.getProperty(prefix + "samples"), 0L),
                parseDouble(p.getProperty(prefix + "expectancyPercent"), 0.0),
                parseDouble(p.getProperty(prefix + "profitFactor"), 0.0),
                p.getProperty(prefix + "reason", "")
        );
    }

    private static String normalizeStrategy(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
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

    private static long parseLong(String value, long fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
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

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static final class ExitProfile {
        final String key;
        final String exitStyle;
        final double partialProfitTargetPercent;
        final double partialExitFraction;
        final double trailingGivebackPercent;
        final double fullProfitLockPercent;
        final double hardStopLossPercent;
        final long maxHoldMs;
        final long samples;
        final double expectancyPercent;
        final double profitFactor;
        final String reason;

        ExitProfile(String key,
                    String exitStyle,
                    double partialProfitTargetPercent,
                    double partialExitFraction,
                    double trailingGivebackPercent,
                    double fullProfitLockPercent,
                    double hardStopLossPercent,
                    long maxHoldMs,
                    long samples,
                    double expectancyPercent,
                    double profitFactor,
                    String reason) {
            this.key = key == null ? "" : key;
            this.exitStyle = exitStyle == null || exitStyle.isBlank() ? "STATIC_DEFAULT" : exitStyle.trim().toUpperCase(Locale.ROOT);
            this.partialProfitTargetPercent = partialProfitTargetPercent;
            this.partialExitFraction = partialExitFraction;
            this.trailingGivebackPercent = trailingGivebackPercent;
            this.fullProfitLockPercent = fullProfitLockPercent;
            this.hardStopLossPercent = hardStopLossPercent;
            this.maxHoldMs = maxHoldMs;
            this.samples = samples;
            this.expectancyPercent = expectancyPercent;
            this.profitFactor = profitFactor;
            this.reason = reason == null ? "" : reason;
        }
    }

    public static final class ExitPlan {
        public final String exitStyle;
        public final double partialProfitTargetPercent;
        public final double partialExitFraction;
        public final double trailingGivebackPercent;
        public final double fullProfitLockPercent;
        public final double hardStopLossPercent;
        public final long maxHoldMs;
        public final String reason;

        ExitPlan(String exitStyle,
                 double partialProfitTargetPercent,
                 double partialExitFraction,
                 double trailingGivebackPercent,
                 double fullProfitLockPercent,
                 double hardStopLossPercent,
                 long maxHoldMs,
                 String reason) {
            this.exitStyle = exitStyle == null ? "STATIC_DEFAULT" : exitStyle;
            this.partialProfitTargetPercent = partialProfitTargetPercent;
            this.partialExitFraction = partialExitFraction;
            this.trailingGivebackPercent = trailingGivebackPercent;
            this.fullProfitLockPercent = fullProfitLockPercent;
            this.hardStopLossPercent = hardStopLossPercent;
            this.maxHoldMs = maxHoldMs;
            this.reason = reason == null ? "" : reason;
        }
    }
}
