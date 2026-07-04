package com.bot.intelligence;

import com.bot.master.MasterStrategyDecision;
import com.bot.master.StrategyAction;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.NewsEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records every pre-trade calibration block/shrink and opens a tagged shadow
 * probe so after-hours learning can measure missed profit versus avoided loss.
 */
public final class PreTradeCalibrationAuditJournal {
    private static final PreTradeCalibrationAuditJournal INSTANCE = new PreTradeCalibrationAuditJournal();

    private final Path auditPath = Path.of(env("PRE_TRADE_CALIBRATION_AUDIT_PATH",
            "logs/pre_trade_calibration_audit.csv"));
    private final ShadowTradeJournal shadowTradeJournal = ShadowTradeJournal.getInstance();
    private final Map<String, Long> recent = new ConcurrentHashMap<>();
    private final boolean enabled = envBool("PRE_TRADE_CALIBRATION_AUDIT_ENABLED", true);
    private final boolean shadowEnabled = envBool("PRE_TRADE_CALIBRATION_AUDIT_SHADOW_ENABLED", true);
    private final long suppressMs = Math.max(0L, envLong("PRE_TRADE_CALIBRATION_AUDIT_SUPPRESS_MS", 15_000L));

    private PreTradeCalibrationAuditJournal() {
        ensureHeader();
    }

    public static PreTradeCalibrationAuditJournal getInstance() {
        return INSTANCE;
    }

    public void recordBlocked(StrategyContext context,
                              StrategySignal original,
                              PreTradeCalibrationModel.CalibrationReview review,
                              String phase) {
        record(context, original, null, review, phase, "BLOCKED", 0.0, null);
    }

    public void recordBlockedAtPrice(StrategySignal original,
                                     PreTradeCalibrationModel.CalibrationReview review,
                                     String phase,
                                     double referencePrice,
                                     NewsEvent news) {
        record(null, original, null, review, phase, "BLOCKED", referencePrice, news);
    }

    public void recordAdjusted(StrategyContext context,
                               StrategySignal original,
                               StrategySignal adjusted,
                               PreTradeCalibrationModel.CalibrationReview review,
                               String phase) {
        String intervention = adjusted != null && adjusted.getSuggestedQuantity() < originalQuantity(original)
                ? "SHRUNK"
                : "CALIBRATED";
        record(context, original, adjusted, review, phase, intervention, 0.0, null);
    }

    private synchronized void record(StrategyContext context,
                                     StrategySignal original,
                                     StrategySignal adjusted,
                                     PreTradeCalibrationModel.CalibrationReview review,
                                     String phase,
                                     String intervention,
                                     double explicitReferencePrice,
                                     NewsEvent explicitNews) {
        if (!enabled || original == null || original.getAction() != StrategyAction.BUY) {
            return;
        }
        String ticker = normalizeTicker(original.getTicker());
        if (ticker.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        String originalStrategy = normalizeStrategy(original.getStrategyName());
        String auditStrategy = auditStrategy(originalStrategy);
        String regime = review == null ? "" : review.regime;
        String confidenceBucket = review == null ? "" : review.confidenceBucket;
        String key = ticker + "|" + originalStrategy + "|" + intervention + "|" + phase + "|" + regime + "|" + confidenceBucket;
        Long previous = recent.put(key, now);
        if (previous != null && now - previous < suppressMs) {
            return;
        }
        recent.entrySet().removeIf(e -> now - e.getValue() > Math.max(60_000L, suppressMs * 4L));

        int originalQuantity = originalQuantity(original);
        int adjustedQuantity = adjusted == null ? 0 : Math.max(0, adjusted.getSuggestedQuantity());
        double referencePrice = explicitReferencePrice > 0.0 && Double.isFinite(explicitReferencePrice)
                ? explicitReferencePrice
                : context == null ? 0.0 : context.getLastPrice();
        NewsEvent news = explicitNews != null ? explicitNews : context == null ? null : context.getLatestNews();
        String auditId = "PTC-" + ticker + "-" + System.currentTimeMillis() + "-" +
                UUID.randomUUID().toString().substring(0, 8);
        String shadowTradeId = "";
        boolean shadowOpened = false;
        if (shadowEnabled && referencePrice > 0.0 && Double.isFinite(referencePrice)) {
            StrategySignal auditSignal = StrategySignal.buy(
                    auditStrategy,
                    ticker,
                    original.getDirection(),
                    original.getConfidence(),
                    original.getExpectedMovePercent(),
                    originalQuantity,
                    "PRE_TRADE_CALIBRATION_AUDIT auditId=" + auditId +
                            " originalStrategy=" + originalStrategy +
                            " phase=" + safe(phase) +
                            " intervention=" + safe(intervention) +
                            " originalReason=" + safe(original.getReason())
            );
            MasterStrategyDecision auditDecision = MasterStrategyDecision.buy(
                    ticker,
                    auditSignal,
                    List.of(auditSignal),
                    "PRE_TRADE_CALIBRATION_AUDIT auditId=" + auditId +
                            " originalStrategy=" + originalStrategy +
                            " intervention=" + safe(intervention) +
                            " review=" + safe(review == null ? "" : review.reason)
            );
            shadowTradeJournal.observePrice(ticker, referencePrice);
            shadowTradeId = shadowTradeJournal.recordDecision(
                    auditDecision,
                    news,
                    "PRE_TRADE_CALIBRATION_AUDIT_" + safe(intervention),
                    referencePrice,
                    false
            );
            shadowOpened = shadowTradeId != null && !shadowTradeId.isBlank();
        }

        append(List.of(
                Instant.ofEpochMilli(now).toString(),
                auditId,
                safe(phase),
                safe(intervention),
                ticker,
                originalStrategy,
                auditStrategy,
                original.getDirection() == null ? "" : original.getDirection().name(),
                regime,
                confidenceBucket,
                Integer.toString(review == null ? 0 : review.samples),
                fmt(original.getConfidence()),
                fmt(adjusted == null ? 0.0 : adjusted.getConfidence()),
                fmt(original.getExpectedMovePercent()),
                fmt(adjusted == null ? 0.0 : adjusted.getExpectedMovePercent()),
                Integer.toString(originalQuantity),
                Integer.toString(adjustedQuantity),
                fmt(review == null ? 1.0 : review.sizingMultiplier),
                fmt(review == null ? 1.0 : review.confidenceMultiplier),
                fmt(review == null ? 1.0 : review.expectedMoveMultiplier),
                fmt(referencePrice),
                shadowTradeId == null ? "" : shadowTradeId,
                Boolean.toString(shadowOpened),
                safe(review == null ? "" : review.reason),
                safe(original.getReason())
        ));
    }

    private void ensureHeader() {
        try {
            Path parent = auditPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(auditPath)) {
                Files.writeString(auditPath,
                        "timestamp,auditId,phase,intervention,ticker,originalStrategy,auditStrategy,direction,regime,confidenceBucket,calibrationSamples,originalConfidence,adjustedConfidence,originalExpectedMovePercent,adjustedExpectedMovePercent,originalQuantity,adjustedQuantity,sizingMultiplier,confidenceMultiplier,expectedMoveMultiplier,referencePrice,shadowTradeId,shadowOpened,reason,signalReason" +
                                System.lineSeparator(),
                        StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.out.println("PRE TRADE CALIBRATION AUDIT INIT FAILED: " + e.getMessage());
        }
    }

    private void append(List<String> values) {
        try {
            Path parent = auditPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(auditPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) {
                        writer.write(',');
                    }
                    writer.write(csv(values.get(i)));
                }
                writer.newLine();
            }
        } catch (IOException e) {
            System.out.println("PRE TRADE CALIBRATION AUDIT WRITE FAILED: " + e.getMessage());
        }
    }

    private static int originalQuantity(StrategySignal signal) {
        return Math.max(1, signal == null ? 0 : signal.getSuggestedQuantity());
    }

    private static String auditStrategy(String originalStrategy) {
        String base = normalizeStrategy(originalStrategy);
        if (base.isBlank()) {
            base = "UNKNOWN";
        }
        return base + "_PRE_TRADE_CALIBRATION_AUDIT";
    }

    private static String normalizeTicker(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeStrategy(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.8f", Double.isFinite(value) ? value : 0.0);
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
}
