package com.bot.intelligence;

import com.bot.master.MasterStrategyDecision;
import com.bot.master.StrategySignal;
import com.bot.master.StrategyAction;
import com.bot.model.NewsEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;

/**
 * Captures the reason behind every trade-quality decision so the nightly AI can
 * compare winners, losers, missed opportunities, catalyst type, market regime,
 * and selected strategy without depending on console logs.
 */
public final class TradeQualityJournal {
    private static final TradeQualityJournal INSTANCE = new TradeQualityJournal();

    private final Path path;

    private TradeQualityJournal() {
        this.path = Paths.get(System.getenv().getOrDefault("TRADE_QUALITY_JOURNAL_PATH", "logs/trade_quality.csv"));
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(path)) {
                try (BufferedWriter writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    writer.write("timestamp,ticker,trigger,action,strategy,direction,confidence,expectedMovePercent,priorityScore,catalystScore,source,headline,marketRegime,regimeReason,decisionReason");
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize trade quality journal: " + path, e);
        }
    }

    public static TradeQualityJournal getInstance() {
        return INSTANCE;
    }

    public synchronized void record(NewsEvent news, MasterStrategyDecision decision, String trigger) {
        if (!envBoolean("TRADE_QUALITY_JOURNAL_ENABLED", true)) {
            return;
        }
        try {
            StrategySignal signal = decision == null ? null : decision.getWinningSignal();
            MarketRegimeSnapshot regime = MarketRegimeEngine.getInstance().currentSnapshot();
            String ticker = signal != null ? signal.getTicker() : decision != null ? decision.getTicker() : news != null ? news.getTicker() : "UNKNOWN";
            String action = decision == null || decision.getAction() == null ? "UNKNOWN" : decision.getAction().name();
            String strategy = signal == null ? "NONE" : signal.getStrategyName();
            String direction = signal == null || signal.getDirection() == null ? "NONE" : signal.getDirection().name();
            double confidence = signal == null ? 0.0 : signal.getConfidence();
            double expectedMove = signal == null ? 0.0 : signal.getExpectedMovePercent();
            double priority = signal == null ? 0.0 : signal.priorityScore();
            double catalystScore = news == null ? 0.0 : news.getCatalystScore();
            append(
                    Instant.now().toString(),
                    ticker,
                    trigger,
                    action,
                    strategy,
                    direction,
                    fmt(confidence),
                    fmt(expectedMove),
                    fmt(priority),
                    fmt(catalystScore),
                    news == null ? "" : news.getSource(),
                    news == null ? "" : news.getHeadline(),
                    regime.getRegime().name(),
                    regime.getReason(),
                    decision == null ? "" : decision.getReason()
            );
        } catch (Exception e) {
            System.err.println("TRADE QUALITY JOURNAL ERROR: " + e.getMessage());
        }
    }

    public synchronized void recordExecution(String ticker, String strategy, String side, int requestedQty, int finalQty, double price, boolean filled, long latencyMs, String reason) {
        if (!envBoolean("TRADE_QUALITY_JOURNAL_ENABLED", true)) {
            return;
        }
        try {
            MarketRegimeSnapshot regime = MarketRegimeEngine.getInstance().currentSnapshot();
            append(
                    Instant.now().toString(),
                    ticker,
                    "EXECUTION_QUALITY",
                    filled ? "FILLED" : "NOT_FILLED",
                    strategy,
                    side,
                    "0.0000",
                    "0.0000",
                    "0.0000",
                    "0.0000",
                    "EXECUTION",
                    "requestedQty=" + requestedQty + " finalQty=" + finalQty + " price=" + fmt(price) + " latencyMs=" + latencyMs,
                    regime.getRegime().name(),
                    regime.getReason(),
                    reason
            );
        } catch (Exception e) {
            System.err.println("EXECUTION QUALITY JOURNAL ERROR: " + e.getMessage());
        }
    }

    private void append(String... values) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (int i = 0; i < values.length; i++) {
                if (i > 0) writer.write(',');
                writer.write(clean(values[i]));
            }
            writer.newLine();
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String fmt(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "0.0000";
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        return "true".equalsIgnoreCase(value.trim());
    }
}
