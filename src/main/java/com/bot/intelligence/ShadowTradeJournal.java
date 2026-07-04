package com.bot.intelligence;

import com.bot.master.MasterStrategyDecision;
import com.bot.master.StrategyAction;
import com.bot.master.StrategySignal;
import com.bot.model.NewsEvent;
import com.bot.model.TradeDirection;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Clean shadow-trade ledger for strategy decisions.
 *
 * This never submits broker orders. It records what the system would have
 * traded, then closes the simulated position from later observed prices so
 * paper-gate promotion can use clean, isolated evidence.
 */
public final class ShadowTradeJournal {
    private static final ShadowTradeJournal INSTANCE = new ShadowTradeJournal();

    private final Path decisionPath;
    private final Path outcomePath;
    private final Map<String, ShadowPosition> active = new LinkedHashMap<>();
    private final boolean enabled;
    private final long minDuplicateMs;
    private final long maxHoldMs;
    private final double takeProfitPercent;
    private final double stopLossPercent;
    private final double trailingGivebackPercent;
    private final int maxOpenPositions;

    private ShadowTradeJournal() {
        this.enabled = envBoolean("SHADOW_TRADE_JOURNAL_ENABLED", true);
        this.decisionPath = Path.of(env("SHADOW_TRADE_DECISION_PATH", "logs/shadow_trade_decisions.csv"));
        this.outcomePath = Path.of(env("SHADOW_TRADE_OUTCOME_PATH", "logs/shadow_trade_outcomes.csv"));
        this.minDuplicateMs = envLong("SHADOW_TRADE_MIN_DUPLICATE_MS", 120_000L);
        this.maxHoldMs = envLong("SHADOW_TRADE_MAX_HOLD_MS", 12L * 60L * 1000L);
        this.takeProfitPercent = envDouble("SHADOW_TRADE_TAKE_PROFIT_PERCENT", 0.0125);
        this.stopLossPercent = Math.abs(envDouble("SHADOW_TRADE_STOP_LOSS_PERCENT", 0.0075));
        this.trailingGivebackPercent = Math.abs(envDouble("SHADOW_TRADE_TRAILING_GIVEBACK_PERCENT", 0.0060));
        this.maxOpenPositions = Math.max(1, envInt("SHADOW_TRADE_MAX_OPEN_POSITIONS", 250));
        if (enabled) {
            ensureHeaders();
        }
    }

    public static ShadowTradeJournal getInstance() {
        return INSTANCE;
    }

    public synchronized String recordDecision(MasterStrategyDecision decision,
                                              NewsEvent news,
                                              String trigger,
                                              double referencePrice,
                                              boolean liveOrdersEnabled) {
        if (!enabled || decision == null || decision.getAction() != StrategyAction.BUY) {
            return "";
        }
        StrategySignal signal = decision.getWinningSignal();
        if (signal == null || referencePrice <= 0.0 || !Double.isFinite(referencePrice)) {
            return "";
        }
        String ticker = normalize(signal.getTicker(), decision.getTicker());
        String strategy = normalize(signal.getStrategyName(), "UNKNOWN");
        if (ticker.isBlank() || "UNKNOWN".equals(ticker)) {
            return "";
        }

        long now = System.currentTimeMillis();
        String dedupeKey = ticker + "|" + strategy + "|" + directionName(signal);
        ShadowPosition existing = active.get(dedupeKey);
        if (existing != null) {
            if (now - existing.openedAtMs < minDuplicateMs) {
                existing.lastDecisionReason = decision.getReason();
                return existing.shadowId;
            }
            existing.observe(referencePrice);
            existing.closeReason = "SHADOW_REPLACED_BY_NEW_SIGNAL";
            active.remove(dedupeKey);
            appendOutcome("CLOSE", existing, now, referencePrice, realizedPnl(existing, referencePrice), false, existing.closeReason);
        }
        if (active.size() >= maxOpenPositions) {
            closeOldest(now, referencePrice, "SHADOW_CAPACITY_ROTATION");
        }

        int quantity = Math.max(1, signal.getSuggestedQuantity());
        ShadowPosition position = new ShadowPosition(
                shadowId(ticker, strategy),
                dedupeKey,
                ticker,
                strategy,
                directionName(signal),
                quantity,
                referencePrice,
                now,
                signal.getConfidence(),
                signal.getExpectedMovePercent(),
                signal.priorityScore(),
                trigger,
                decision.getReason(),
                signal.getReason(),
                liveOrdersEnabled
        );
        active.put(dedupeKey, position);
        appendDecision(position, news);
        appendOutcome("OPEN", position, now, referencePrice, 0.0, false, "SHADOW_POSITION_OPENED");
        return position.shadowId;
    }

    public synchronized void observePrice(String ticker, double price) {
        if (!enabled || ticker == null || ticker.isBlank() || price <= 0.0 || !Double.isFinite(price)) {
            return;
        }
        String normalizedTicker = normalize(ticker, "UNKNOWN");
        long now = System.currentTimeMillis();
        List<ShadowPosition> toClose = new ArrayList<>();
        for (ShadowPosition position : active.values()) {
            if (!position.ticker.equals(normalizedTicker)) {
                continue;
            }
            position.observe(price);
            String closeReason = closeReason(position, now);
            if (closeReason != null) {
                position.closeReason = closeReason;
                toClose.add(position);
            }
        }
        for (ShadowPosition position : toClose) {
            active.remove(position.key);
            appendOutcome("CLOSE", position, now, price, realizedPnl(position, price), false, position.closeReason);
        }
    }

    public synchronized int activeCount() {
        return active.size();
    }

    public Path decisionPath() {
        return decisionPath;
    }

    public Path outcomePath() {
        return outcomePath;
    }

    private void closeOldest(long now, double fallbackPrice, String reason) {
        Iterator<ShadowPosition> it = active.values().iterator();
        if (!it.hasNext()) {
            return;
        }
        ShadowPosition oldest = it.next();
        it.remove();
        double price = oldest.lastPrice > 0.0 ? oldest.lastPrice : fallbackPrice;
        appendOutcome("CLOSE", oldest, now, price, realizedPnl(oldest, price), false, reason);
    }

    private String closeReason(ShadowPosition p, long now) {
        double pnl = p.currentPnlPercent();
        if (pnl >= takeProfitPercent) {
            return "SHADOW_TAKE_PROFIT";
        }
        if (pnl <= -stopLossPercent) {
            return "SHADOW_STOP_LOSS";
        }
        if (p.maxGainPercent >= takeProfitPercent * 0.60 && pnl <= p.maxGainPercent - trailingGivebackPercent) {
            return "SHADOW_TRAILING_GIVEBACK";
        }
        if (now - p.openedAtMs >= maxHoldMs) {
            return "SHADOW_MAX_HOLD";
        }
        return null;
    }

    private double realizedPnl(ShadowPosition p, double exitPrice) {
        if (p == null || p.entryPrice <= 0.0 || exitPrice <= 0.0) {
            return 0.0;
        }
        double gross = p.isShort()
                ? (p.entryPrice - exitPrice) * p.quantity
                : (exitPrice - p.entryPrice) * p.quantity;
        double notional = Math.max(p.entryPrice * p.quantity, 0.0);
        double feeEstimate = notional * envDouble("SHADOW_TRADE_FEE_RATE", 0.0001);
        return gross - feeEstimate;
    }

    private void appendDecision(ShadowPosition p, NewsEvent news) {
        append(
                decisionPath,
                List.of(
                        Instant.ofEpochMilli(p.openedAtMs).toString(),
                        "OPEN_DECISION",
                        p.shadowId,
                        p.ticker,
                        p.strategy,
                        p.direction,
                        p.trigger,
                        fmt(p.entryPrice),
                        Integer.toString(p.quantity),
                        fmt(p.confidence),
                        fmt(p.expectedMovePercent),
                        fmt(p.priorityScore),
                        fmt(news == null ? 0.0 : news.getCatalystScore()),
                        news == null ? "" : news.getSource(),
                        news == null ? "" : news.getHeadline(),
                        p.decisionReason,
                        p.signalReason,
                        Boolean.toString(p.liveOrdersEnabled)
                )
        );
    }

    private void appendOutcome(String eventType,
                               ShadowPosition p,
                               long eventAtMs,
                               double eventPrice,
                               double realizedPnlDollars,
                               boolean partialProfitTaken,
                               String reason) {
        double currentPnl = p.pnlPercentAt(eventPrice);
        append(
                outcomePath,
                List.of(
                        Instant.ofEpochMilli(eventAtMs).toString(),
                        eventType,
                        p.ticker,
                        p.strategy,
                        p.isShort() ? "SHORT" : "LONG",
                        Long.toString(p.openedAtMs),
                        Long.toString(eventAtMs),
                        Long.toString(Math.max(0L, eventAtMs - p.openedAtMs)),
                        Integer.toString(p.quantity),
                        Integer.toString(p.quantity),
                        Integer.toString(p.quantity),
                        fmt(p.entryPrice),
                        fmt(eventPrice),
                        fmt(p.peakPrice),
                        fmt(p.troughPrice),
                        fmt(currentPnl),
                        fmt(p.maxGainPercent),
                        fmt(p.maxDrawdownPercent),
                        fmt(realizedPnlDollars),
                        Boolean.toString(partialProfitTaken),
                        "false",
                        reason == null ? "" : reason + " shadowTradeId=" + p.shadowId +
                                " trigger=" + p.trigger +
                                " liveOrdersEnabled=" + p.liveOrdersEnabled
                )
        );
    }

    private void ensureHeaders() {
        ensureHeader(decisionPath,
                "timestamp,event,shadowTradeId,ticker,strategy,direction,trigger,entryPrice,quantity,confidence,expectedMovePercent,priorityScore,catalystScore,source,headline,decisionReason,signalReason,liveOrdersEnabled");
        ensureHeader(outcomePath,
                "timestamp,eventType,ticker,strategyName,side,openedAtMs,eventAtMs,holdMs,initialQuantity,remainingQuantityBeforeEvent,eventQuantity,entryPrice,eventPrice,peakPrice,troughPrice,currentPnlPercent,maxGainPercent,maxDrawdownPercent,realizedPnlDollars,partialProfitTaken,syncedFromBroker,reason");
    }

    private static void ensureHeader(Path path, String header) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(path)) {
                Files.writeString(path, header + System.lineSeparator(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.out.println("SHADOW TRADE JOURNAL INIT FAILED: " + path + " " + e.getMessage());
        }
    }

    private static void append(Path path, List<String> values) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
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
            System.out.println("SHADOW TRADE JOURNAL WRITE FAILED: " + path + " " + e.getMessage());
        }
    }

    private static String shadowId(String ticker, String strategy) {
        String compactStrategy = strategy == null ? "UNKNOWN" : strategy.replaceAll("[^A-Z0-9_]+", "_");
        return "SHADOW-" + ticker + "-" + compactStrategy + "-" + System.currentTimeMillis() + "-" +
                UUID.randomUUID().toString().substring(0, 8);
    }

    private static String directionName(StrategySignal signal) {
        TradeDirection direction = signal == null ? TradeDirection.NO_TRADE : signal.getDirection();
        return direction == TradeDirection.SHORT_STOCK || direction == TradeDirection.LONG_PUT ? "SHORT" : "LONG";
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? "" : fallback.trim().toUpperCase(Locale.ROOT);
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private static String fmt(double value) {
        if (!Double.isFinite(value)) {
            value = 0.0;
        }
        return String.format(Locale.ROOT, "%.8f", value);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
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

    private static final class ShadowPosition {
        final String shadowId;
        final String key;
        final String ticker;
        final String strategy;
        final String direction;
        final int quantity;
        final double entryPrice;
        final long openedAtMs;
        final double confidence;
        final double expectedMovePercent;
        final double priorityScore;
        final String trigger;
        final String signalReason;
        final boolean liveOrdersEnabled;
        String decisionReason;
        String lastDecisionReason;
        String closeReason = "";
        double peakPrice;
        double troughPrice;
        double lastPrice;
        double maxGainPercent;
        double maxDrawdownPercent;

        ShadowPosition(String shadowId,
                       String key,
                       String ticker,
                       String strategy,
                       String direction,
                       int quantity,
                       double entryPrice,
                       long openedAtMs,
                       double confidence,
                       double expectedMovePercent,
                       double priorityScore,
                       String trigger,
                       String decisionReason,
                       String signalReason,
                       boolean liveOrdersEnabled) {
            this.shadowId = shadowId;
            this.key = key;
            this.ticker = ticker;
            this.strategy = strategy;
            this.direction = direction;
            this.quantity = quantity;
            this.entryPrice = entryPrice;
            this.openedAtMs = openedAtMs;
            this.confidence = confidence;
            this.expectedMovePercent = expectedMovePercent;
            this.priorityScore = priorityScore;
            this.trigger = trigger == null ? "" : trigger;
            this.decisionReason = decisionReason == null ? "" : decisionReason;
            this.signalReason = signalReason == null ? "" : signalReason;
            this.liveOrdersEnabled = liveOrdersEnabled;
            this.peakPrice = entryPrice;
            this.troughPrice = entryPrice;
            this.lastPrice = entryPrice;
        }

        void observe(double price) {
            lastPrice = price;
            peakPrice = Math.max(peakPrice, price);
            troughPrice = Math.min(troughPrice, price);
            double pnl = currentPnlPercent();
            maxGainPercent = Math.max(maxGainPercent, pnl);
            maxDrawdownPercent = Math.max(maxDrawdownPercent, Math.max(0.0, -pnl));
        }

        boolean isShort() {
            return "SHORT".equals(direction);
        }

        double currentPnlPercent() {
            return pnlPercentAt(lastPrice);
        }

        double pnlPercentAt(double price) {
            if (entryPrice <= 0.0 || price <= 0.0) {
                return 0.0;
            }
            return isShort() ? (entryPrice - price) / entryPrice : (price - entryPrice) / entryPrice;
        }
    }
}
