package com.bot.intelligence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FeatureJournal {

    private final Path path;
    private boolean headerWritten;
    private final Map<String, Long> recentRows = new ConcurrentHashMap<>();
    private final long duplicateSuppressMs = envLong("FEATURE_JOURNAL_DUPLICATE_SUPPRESS_MS", 5_000L);
    private final long holdDuplicateSuppressMs = envLong("FEATURE_JOURNAL_HOLD_DUPLICATE_SUPPRESS_MS", 120_000L);
    private final long maxJournalBytes = envLong("FEATURE_JOURNAL_MAX_BYTES", 250L * 1024L * 1024L);
    private volatile boolean maxSizeWarningPrinted = false;
    private final boolean autoRotate = !"false".equalsIgnoreCase(System.getenv().getOrDefault("FEATURE_JOURNAL_AUTO_ROTATE", "true"));

    public FeatureJournal() {
        this(Path.of(System.getenv().getOrDefault("FEATURE_JOURNAL_PATH", "logs/market_features.csv")));
    }

    public FeatureJournal(Path path) {
        this.path = path;
        this.headerWritten = Files.exists(path);
    }

    public synchronized void record(MarketFeatureSnapshot snapshot, ProbabilityPrediction prediction, String selectedStrategy, String action) {
        if (snapshot == null) {
            return;
        }

        String rowKey = duplicateKey(snapshot, prediction, selectedStrategy, action);
        long now = System.currentTimeMillis();
        Long previousAt = recentRows.put(rowKey, now);
        long suppressWindow = isLowValueHoldAction(action) ? holdDuplicateSuppressMs : duplicateSuppressMs;
        if (previousAt != null && now - previousAt < suppressWindow) {
            return;
        }
        pruneRecentRows(now);

        try {
            if (maxJournalBytes > 0 && Files.exists(path) && Files.size(path) >= maxJournalBytes) {
                if (autoRotate && rotateOversizedJournal()) {
                    headerWritten = false;
                    maxSizeWarningPrinted = false;
                } else {
                    if (!maxSizeWarningPrinted) {
                        maxSizeWarningPrinted = true;
                        System.out.println("FEATURE JOURNAL MAX SIZE REACHED: path=" + path +
                                " maxBytes=" + maxJournalBytes +
                                " action=skip_new_feature_rows_until_file_is_archived_or_FEATURE_JOURNAL_MAX_BYTES_is_increased");
                    }
                    return;
                }
            }

            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            String extraHeader = ",pProfitTarget,pStopLoss,expectedValuePercent,modelConfidence,selectedStrategy,selectedAction,modelReason";
            if (!headerWritten) {
                Files.writeString(path, snapshot.csvHeader() + extraHeader + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                headerWritten = true;
            }

            String row = snapshot.csvRow() + "," +
                    value(prediction == null ? 0.0 : prediction.getProbabilityHitProfitTarget()) + "," +
                    value(prediction == null ? 0.0 : prediction.getProbabilityHitStopLoss()) + "," +
                    value(prediction == null ? 0.0 : prediction.getExpectedValuePercent()) + "," +
                    value(prediction == null ? 0.0 : prediction.confidence()) + "," +
                    escape(selectedStrategy) + "," +
                    escape(action) + "," +
                    escape(prediction == null ? "" : prediction.getReason()) + System.lineSeparator();

            Files.writeString(path, row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("FEATURE JOURNAL WRITE FAILED: " + e.getMessage());
        }
    }


    private boolean rotateOversizedJournal() {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String fileName = path.getFileName() == null ? "market_features.csv" : path.getFileName().toString();
            String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                    .format(java.time.LocalDateTime.now());
            Path archive = (parent == null ? Path.of(fileName + "." + stamp + ".archive.csv")
                    : parent.resolve(fileName + "." + stamp + ".archive.csv"));
            Files.move(path, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("FEATURE JOURNAL AUTO-ROTATED: old=" + archive + " new=" + path + " maxBytes=" + maxJournalBytes);
            return true;
        } catch (Exception e) {
            System.out.println("FEATURE JOURNAL AUTO-ROTATE FAILED: " + e.getMessage());
            return false;
        }
    }

    private String duplicateKey(MarketFeatureSnapshot snapshot, ProbabilityPrediction prediction, String selectedStrategy, String action) {
        String reason = prediction == null ? "" : prediction.getReason();
        String normalizedAction = action == null ? "" : action.trim().toUpperCase();
        if (isLowValueHoldAction(normalizedAction)) {
            // Watchlist rechecks were producing hundreds of MB of near-identical
            // HOLD rows. Bucket low-value HOLD-style rows by ticker/strategy/reason
            // so the evolution engine still receives the fact that a setup was
            // rejected, without drowning useful BUY/CLOSE/rotation outcomes.
            return snapshot.ticker + "|LOW_VALUE_HOLD|" + selectedStrategy + "|" + reason;
        }
        long bucket = snapshot.timestamp / 1_000L;
        return snapshot.ticker + "|" + bucket + "|" + selectedStrategy + "|" + action + "|" + reason;
    }

    private static boolean isLowValueHoldAction(String action) {
        if (action == null || action.isBlank()) {
            return false;
        }
        String normalized = action.trim().toUpperCase();
        return normalized.contains("HOLD") ||
                normalized.contains("WATCH") ||
                normalized.contains("NO_ACTIONABLE") ||
                normalized.contains("CONFIDENCE_BLOCK") ||
                normalized.contains("WARMUP") ||
                normalized.contains("CATALYST_BLOCK") ||
                normalized.contains("SESSION_BUY_BLOCK") ||
                normalized.contains("FAILED_BUY_COOLDOWN_BLOCK");
    }

    private void pruneRecentRows(long now) {
        if (recentRows.size() < 10_000) {
            return;
        }
        recentRows.entrySet().removeIf(entry -> now - entry.getValue() > duplicateSuppressMs * 4);
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String value(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0.0";
        }
        return Double.toString(value);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace('\n', ' ').replace('\r', ' ');
        if (cleaned.contains(",") || cleaned.contains("\"")) {
            return "\"" + cleaned.replace("\"", "\"\"") + "\"";
        }
        return cleaned;
    }
}
