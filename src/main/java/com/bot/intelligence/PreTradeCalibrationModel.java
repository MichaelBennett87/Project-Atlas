package com.bot.intelligence;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.model.NewsEvent;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime pre-trade confidence calibration gate.
 */
public final class PreTradeCalibrationModel {
    private static final PreTradeCalibrationModel INSTANCE = new PreTradeCalibrationModel();

    private final Path policyPath = Path.of(System.getenv().getOrDefault(
            "PRE_TRADE_CALIBRATION_POLICY_PATH", "logs/pre_trade_calibration_policy.properties"));
    private final Map<String, Profile> profiles = new ConcurrentHashMap<>();
    private volatile long lastLoadAt = 0L;

    private PreTradeCalibrationModel() {
    }

    public static PreTradeCalibrationModel getInstance() {
        return INSTANCE;
    }

    public CalibrationReview review(StrategySignal signal, StrategyContext context) {
        if (!envBoolean("PRE_TRADE_CALIBRATION_MODEL_ENABLED", true)) {
            return CalibrationReview.allow("UNKNOWN", "UNKNOWN", "ANY", 1.0, 1.0, 1.0,
                    0, "pre-trade calibration disabled");
        }
        if (signal == null) {
            return CalibrationReview.allow("UNKNOWN", "UNKNOWN", "ANY", 1.0, 1.0, 1.0,
                    0, "no signal");
        }
        reloadIfNeeded();
        String strategy = normalizeStrategy(signal.getStrategyName());
        String regime = classifyRegime(context);
        String bucket = confidenceBucket(signal.getConfidence());
        Profile profile = selectProfile(strategy, regime, bucket);
        if (profile == null) {
            return CalibrationReview.allow(strategy, regime, bucket, 1.0, 1.0, 1.0,
                    0, "no pre-trade calibration profile");
        }
        boolean block = profile.blocked
                && profile.samples >= envInt("PRE_TRADE_CALIBRATION_RUNTIME_MIN_BLOCK_SAMPLES", 16);
        double edge = Math.max(0.0, signal.getConfidence() - profile.actualWinRate);
        boolean severeOverconfidence = profile.samples >= envInt("PRE_TRADE_CALIBRATION_RUNTIME_MIN_OVERCONFIDENCE_SAMPLES", 12)
                && edge >= envDouble("PRE_TRADE_CALIBRATION_RUNTIME_MAX_OVERCONFIDENCE", 0.35)
                && profile.expectancyPercent < 0.0;
        if (block || severeOverconfidence) {
            return CalibrationReview.block(strategy, regime, bucket, profile.samples,
                    profile.reason + " liveConfidence=" + fmt(signal.getConfidence()) +
                            " actualWinRate=" + fmt(profile.actualWinRate) +
                            " decision=" + profile.decision);
        }

        double sizing = profile.sizingMultiplier;
        double confidence = profile.confidenceMultiplier;
        double expectedMove = profile.expectedMoveMultiplier;
        if (edge > envDouble("PRE_TRADE_CALIBRATION_RUNTIME_SOFT_OVERCONFIDENCE", 0.20)
                && profile.expectancyPercent <= envDouble("PRE_TRADE_CALIBRATION_RUNTIME_SOFT_EXPECTANCY", 0.0)) {
            double penalty = Math.min(0.30, edge * 0.60);
            sizing = Math.min(sizing, Math.max(0.35, 1.0 - penalty));
            confidence = Math.min(confidence, Math.max(0.50, 1.0 - penalty));
            expectedMove = Math.min(expectedMove, Math.max(0.45, 1.0 - penalty * 0.75));
        }
        return CalibrationReview.allow(strategy, regime, bucket, sizing, confidence, expectedMove,
                profile.samples,
                profile.reason + " profile=" + profile.profileKey +
                        " decision=" + profile.decision +
                        " liveConfidence=" + fmt(signal.getConfidence()) +
                        " actualWinRate=" + fmt(profile.actualWinRate));
    }

    private Profile selectProfile(String strategy, String regime, String bucket) {
        List<String> keys = new ArrayList<>();
        keys.add(profileKey("STRATEGY_REGIME_CONFIDENCE", strategy, regime, bucket));
        keys.add(profileKey("STRATEGY_CONFIDENCE", strategy, "ANY", bucket));
        keys.add(profileKey("STRATEGY_REGIME", strategy, regime, "ANY"));
        keys.add(profileKey("REGIME_CONFIDENCE", "ANY", regime, bucket));
        keys.add(profileKey("STRATEGY", strategy, "ANY", "ANY"));
        keys.add(profileKey("REGIME", "ANY", regime, "ANY"));
        keys.add(profileKey("CONFIDENCE", "ANY", "ANY", bucket));
        keys.add(profileKey("GLOBAL", "ANY", "ANY", "ANY"));
        int minSpecificSamples = envInt("PRE_TRADE_CALIBRATION_RUNTIME_MIN_SPECIFIC_SAMPLES", 8);
        Profile fallback = null;
        for (String key : keys) {
            Profile p = profiles.get(key);
            if (p == null) {
                continue;
            }
            if (fallback == null) {
                fallback = p;
            }
            if (p.samples >= minSpecificSamples || "GLOBAL".equals(p.scope)) {
                return p;
            }
        }
        return fallback;
    }

    private void reloadIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastLoadAt < envLong("PRE_TRADE_CALIBRATION_POLICY_RELOAD_MS", 30_000L)) {
            return;
        }
        lastLoadAt = now;
        Map<String, Profile> loaded = new ConcurrentHashMap<>();
        if (Files.exists(policyPath)) {
            try (InputStream in = Files.newInputStream(policyPath)) {
                Properties p = new Properties();
                p.load(in);
                for (String key : p.stringPropertyNames()) {
                    if (!key.startsWith("profile.") || !key.endsWith(".scope")) {
                        continue;
                    }
                    String id = key.substring("profile.".length(), key.length() - ".scope".length());
                    String prefix = "profile." + id + ".";
                    Profile profile = profile(p, prefix);
                    loaded.put(profile.profileKey, profile);
                }
            } catch (Exception e) {
                System.out.println("PRE TRADE CALIBRATION POLICY LOAD FAILED: " + policyPath + " " + e.getMessage());
            }
        }
        profiles.clear();
        profiles.putAll(loaded);
    }

    private static Profile profile(Properties p, String prefix) {
        String scope = normalize(p.getProperty(prefix + "scope", "GLOBAL"));
        String strategy = normalizeStrategy(p.getProperty(prefix + "strategy", "ANY"));
        String regime = normalizeRegime(p.getProperty(prefix + "regime", "ANY"));
        String bucket = normalize(p.getProperty(prefix + "confidenceBucket", "ANY"));
        String profileKey = profileKey(scope, strategy, regime, bucket);
        return new Profile(
                profileKey,
                scope,
                strategy,
                regime,
                bucket,
                p.getProperty(prefix + "decision", "ALLOW_CALIBRATION"),
                Boolean.parseBoolean(p.getProperty(prefix + "blocked", "false")),
                parseInt(p.getProperty(prefix + "samples"), 0),
                parseDouble(p.getProperty(prefix + "avgPredictedConfidence"), 0.0),
                parseDouble(p.getProperty(prefix + "actualWinRate"), 0.0),
                parseDouble(p.getProperty(prefix + "calibrationError"), 0.0),
                parseDouble(p.getProperty(prefix + "expectancyPercent"), 0.0),
                parseDouble(p.getProperty(prefix + "profitFactor"), 0.0),
                parseDouble(p.getProperty(prefix + "sizingMultiplier"), 1.0),
                parseDouble(p.getProperty(prefix + "confidenceMultiplier"), 1.0),
                parseDouble(p.getProperty(prefix + "expectedMoveMultiplier"), 1.0),
                p.getProperty(prefix + "reason", "")
        );
    }

    private static String classifyRegime(StrategyContext context) {
        if (context == null) {
            MarketRegimeSnapshot snapshot = MarketRegimeEngine.getInstance().currentSnapshot();
            return snapshot == null || snapshot.getRegime() == null ? "UNKNOWN" : normalizeRegime(snapshot.getRegime().name());
        }
        List<Bar> bars = context.getBars();
        if (bars == null || bars.size() < 3) {
            return "LOW_DATA";
        }
        Bar last = bars.get(bars.size() - 1);
        Bar back3 = bars.get(Math.max(0, bars.size() - 4));
        double return3 = back3.close <= 0.0 || last.close <= 0.0 ? 0.0 : (last.close - back3.close) / back3.close;
        double avgVolume = 0.0;
        int volumeCount = 0;
        int start = Math.max(0, bars.size() - 21);
        for (int i = start; i < bars.size() - 1; i++) {
            Bar b = bars.get(i);
            if (b != null && b.volume > 0.0) {
                avgVolume += b.volume;
                volumeCount++;
            }
        }
        avgVolume = volumeCount <= 0 ? 0.0 : avgVolume / volumeCount;
        double rvol = avgVolume <= 0.0 || last.volume <= 0.0 ? 0.0 : last.volume / avgVolume;
        NewsEvent news = context.getLatestNews();
        boolean freshNews = false;
        boolean negativeNews = false;
        if (news != null) {
            long ageMs = news.getTimestamp() <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - news.getTimestamp());
            freshNews = news.getCatalystScore() > 0.25 || (ageMs > 0L && ageMs <= envLong("PRE_TRADE_CALIBRATION_FRESH_NEWS_MS", 900_000L));
            negativeNews = news.getSentimentScore() <= -0.25 && (ageMs <= 0L || ageMs <= envLong("PRE_TRADE_CALIBRATION_NEGATIVE_NEWS_MS", 900_000L));
        }
        if (negativeNews) {
            return "NEGATIVE_NEWS";
        }
        if (rvol <= 0.0 || (rvol < 0.75 && bars.size() < 20)) {
            return "LOW_VOLUME";
        }
        if (rvol >= 1.8 && Math.abs(return3) > 0.003) {
            return "HIGH_RVOL_MOMENTUM";
        }
        if (freshNews) {
            return "NEWS_CATALYST";
        }
        if (Math.abs(return3) < 0.0015) {
            return "CHOPPY_MEAN_REVERSION";
        }
        return "BALANCED";
    }

    private static String profileKey(String scope, String strategy, String regime, String confidenceBucket) {
        return normalize(scope) + "|" + normalizeStrategy(strategy) + "|" + normalizeRegime(regime) + "|" + normalize(confidenceBucket);
    }

    private static String confidenceBucket(double confidence) {
        double clamped = Math.max(0.0, Math.min(0.999999, confidence));
        int bucket = (int)Math.floor(clamped * 10.0);
        int from = bucket * 10;
        int to = from + 9;
        return String.format(Locale.ROOT, "C%02d_%02d", from, to);
    }

    private static String normalizeStrategy(String raw) {
        if (raw == null || raw.isBlank() || "ANY".equalsIgnoreCase(raw.trim())) {
            return "ANY";
        }
        return raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
    }

    private static String normalizeRegime(String raw) {
        if (raw == null || raw.isBlank() || "ANY".equalsIgnoreCase(raw.trim())) {
            return "ANY";
        }
        return raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
    }

    private static String normalize(String raw) {
        return raw == null || raw.isBlank()
                ? "ANY"
                : raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
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

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
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

    private static String fmt(double value) {
        if (!Double.isFinite(value)) {
            value = 0.0;
        }
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static final class Profile {
        final String profileKey;
        final String scope;
        final String strategy;
        final String regime;
        final String confidenceBucket;
        final String decision;
        final boolean blocked;
        final int samples;
        final double avgPredictedConfidence;
        final double actualWinRate;
        final double calibrationError;
        final double expectancyPercent;
        final double profitFactor;
        final double sizingMultiplier;
        final double confidenceMultiplier;
        final double expectedMoveMultiplier;
        final String reason;

        Profile(String profileKey,
                String scope,
                String strategy,
                String regime,
                String confidenceBucket,
                String decision,
                boolean blocked,
                int samples,
                double avgPredictedConfidence,
                double actualWinRate,
                double calibrationError,
                double expectancyPercent,
                double profitFactor,
                double sizingMultiplier,
                double confidenceMultiplier,
                double expectedMoveMultiplier,
                String reason) {
            this.profileKey = profileKey;
            this.scope = scope;
            this.strategy = strategy;
            this.regime = regime;
            this.confidenceBucket = confidenceBucket;
            this.decision = decision == null ? "ALLOW_CALIBRATION" : decision;
            this.blocked = blocked;
            this.samples = Math.max(0, samples);
            this.avgPredictedConfidence = Math.max(0.0, Math.min(1.0, avgPredictedConfidence));
            this.actualWinRate = Math.max(0.0, Math.min(1.0, actualWinRate));
            this.calibrationError = calibrationError;
            this.expectancyPercent = expectancyPercent;
            this.profitFactor = Math.max(0.0, profitFactor);
            this.sizingMultiplier = Math.max(0.0, Math.min(1.50, sizingMultiplier));
            this.confidenceMultiplier = Math.max(0.0, Math.min(1.50, confidenceMultiplier));
            this.expectedMoveMultiplier = Math.max(0.0, Math.min(1.50, expectedMoveMultiplier));
            this.reason = reason == null ? "" : reason;
        }
    }

    public static final class CalibrationReview {
        public final boolean approved;
        public final String strategy;
        public final String regime;
        public final String confidenceBucket;
        public final double sizingMultiplier;
        public final double confidenceMultiplier;
        public final double expectedMoveMultiplier;
        public final int samples;
        public final String reason;

        private CalibrationReview(boolean approved,
                                  String strategy,
                                  String regime,
                                  String confidenceBucket,
                                  double sizingMultiplier,
                                  double confidenceMultiplier,
                                  double expectedMoveMultiplier,
                                  int samples,
                                  String reason) {
            this.approved = approved;
            this.strategy = strategy == null ? "" : strategy;
            this.regime = regime == null ? "" : regime;
            this.confidenceBucket = confidenceBucket == null ? "" : confidenceBucket;
            this.sizingMultiplier = Math.max(0.0, Math.min(1.50, sizingMultiplier));
            this.confidenceMultiplier = Math.max(0.0, Math.min(1.50, confidenceMultiplier));
            this.expectedMoveMultiplier = Math.max(0.0, Math.min(1.50, expectedMoveMultiplier));
            this.samples = Math.max(0, samples);
            this.reason = reason == null ? "" : reason;
        }

        static CalibrationReview allow(String strategy,
                                       String regime,
                                       String confidenceBucket,
                                       double sizingMultiplier,
                                       double confidenceMultiplier,
                                       double expectedMoveMultiplier,
                                       int samples,
                                       String reason) {
            return new CalibrationReview(true, strategy, regime, confidenceBucket, sizingMultiplier,
                    confidenceMultiplier, expectedMoveMultiplier, samples, reason);
        }

        static CalibrationReview block(String strategy,
                                       String regime,
                                       String confidenceBucket,
                                       int samples,
                                       String reason) {
            return new CalibrationReview(false, strategy, regime, confidenceBucket, 0.0, 0.0, 0.0, samples, reason);
        }
    }
}
