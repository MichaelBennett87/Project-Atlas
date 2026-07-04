package com.bot.journal;

import com.bot.master.MasterStrategyDecision;
import com.bot.master.StrategySignal;
import com.bot.model.CatalystResult;
import com.bot.model.NewsEvent;
import com.bot.model.TradeDirection;
import com.bot.sentiment.SentimentScore;
import com.bot.strategy.TradeDirectionService;

import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;

public class SignalJournal {

    private static final String FILE_NAME = "signals.csv";

    private final TradeDirectionService tradeDirectionService;

    public SignalJournal() {
        this.tradeDirectionService = new TradeDirectionService();
    }

    public void record(
            NewsEvent news,
            SentimentScore sentiment,
            String decision,
            String reason
    ) {
        recordDetailed(
                news,
                sentiment,
                null,
                null,
                null,
                news == null ? -1L : System.currentTimeMillis() - news.getTimestamp(),
                decision,
                reason,
                TradeDirection.NO_TRADE
        );
    }

    public void record(
            NewsEvent news,
            Object score,
            String decision,
            String reason
    ) {
        recordDetailed(
                news,
                null,
                null,
                null,
                null,
                news == null ? -1L : System.currentTimeMillis() - news.getTimestamp(),
                decision,
                reason,
                TradeDirection.NO_TRADE
        );
    }

    public void recordUnifiedDecision(
            NewsEvent news,
            MasterStrategyDecision decision,
            String trigger
    ) {
        if (decision == null) {
            return;
        }

        if (envBoolean("SIGNAL_JOURNAL_DETAILED_UNIFIED", false)) {
            if (decision.getAllSignals() == null || decision.getAllSignals().isEmpty()) {
                recordUnifiedSummary(news, decision, trigger, null);
                return;
            }

            for (StrategySignal signal : decision.getAllSignals()) {
                recordUnifiedSignal(news, signal, decision, trigger);
            }
            return;
        }

        StrategySignal selected = decision.getWinningSignal();
        if (selected == null && decision.getAllSignals() != null && !decision.getAllSignals().isEmpty()) {
            selected = decision.getAllSignals()
                    .stream()
                    .max(java.util.Comparator.comparingDouble(StrategySignal::getConfidence))
                    .orElse(null);
        }

        recordUnifiedSummary(news, decision, trigger, selected);
    }

    public void recordUnifiedSummary(
            NewsEvent news,
            MasterStrategyDecision decision,
            String trigger,
            StrategySignal selected
    ) {
        if (decision == null) {
            return;
        }

        NewsEvent journalNews = news;
        if (journalNews == null) {
            journalNews = new NewsEvent();
            journalNews.setTicker(decision.getTicker());
            journalNews.setHeadline("UNIFIED_" + clean(trigger) + "_SUMMARY");
            journalNews.setTimestamp(System.currentTimeMillis());
            journalNews.setSource("UNIFIED_STRATEGY_ENGINE");
        }

        String selectedStrategy = selected == null ? "UNIFIED_MASTER" : selected.getStrategyName();
        Double score = selected == null ? null : selected.getConfidence();
        TradeDirection direction = selected == null ? TradeDirection.NO_TRADE : selected.getDirection();

        String decisionValue = "UNIFIED_SUMMARY_MASTER_" + decision.getAction();
        String reasonValue = "trigger=" + clean(trigger)
                + " selectedStrategy=" + clean(selectedStrategy)
                + " selectedAction=" + (selected == null ? "NONE" : selected.getAction())
                + " selectedConfidence=" + (selected == null ? "" : selected.getConfidence())
                + " selectedQuantity=" + (selected == null ? "" : selected.getSuggestedQuantity())
                + " signalCount=" + (decision.getAllSignals() == null ? 0 : decision.getAllSignals().size())
                + " masterReason=" + clean(decision.getReason())
                + " selectedReason=" + clean(selected == null ? "" : selected.getReason());

        recordDetailed(
                journalNews,
                null,
                null,
                selectedStrategy,
                score,
                journalNews.getTimestamp() <= 0 ? -1L : System.currentTimeMillis() - journalNews.getTimestamp(),
                decisionValue,
                reasonValue,
                direction
        );
    }

    public void recordUnifiedSignal(
            NewsEvent news,
            StrategySignal signal,
            MasterStrategyDecision decision,
            String trigger
    ) {
        if (signal == null) {
            return;
        }

        NewsEvent journalNews = news;
        if (journalNews == null) {
            journalNews = new NewsEvent();
            journalNews.setTicker(signal.getTicker());
            journalNews.setHeadline("UNIFIED_" + clean(trigger) + "_EVALUATION");
            journalNews.setTimestamp(signal.getCreatedAt());
            journalNews.setSource("UNIFIED_STRATEGY_ENGINE");
        }

        String masterAction = decision == null ? "UNKNOWN" : String.valueOf(decision.getAction());
        String decisionValue = "UNIFIED_" + signal.getAction() + "_MASTER_" + masterAction;
        String reasonValue = "trigger=" + clean(trigger)
                + " strategy=" + clean(signal.getStrategyName())
                + " signalAction=" + signal.getAction()
                + " confidence=" + signal.getConfidence()
                + " expectedMovePercent=" + signal.getExpectedMovePercent()
                + " suggestedQuantity=" + signal.getSuggestedQuantity()
                + " signalReason=" + clean(signal.getReason())
                + " masterReason=" + clean(decision == null ? "" : decision.getReason());

        recordDetailed(
                journalNews,
                null,
                null,
                signal.getStrategyName(),
                signal.priorityScore(),
                journalNews.getTimestamp() <= 0 ? -1L : System.currentTimeMillis() - journalNews.getTimestamp(),
                decisionValue,
                reasonValue,
                signal.getDirection()
        );
    }

    public void recordDetailed(
            NewsEvent news,
            SentimentScore sentiment,
            CatalystResult catalyst,
            Object marketQuality,
            Double rankScore,
            long ageMs,
            String decision,
            String reason
    ) {
        TradeDirection direction =
                tradeDirectionService.resolve(
                        catalyst,
                        sentiment
                );

        recordDetailed(
                news,
                sentiment,
                catalyst,
                marketQuality,
                rankScore,
                ageMs,
                decision,
                reason,
                direction
        );
    }

    public void recordDetailed(
            NewsEvent news,
            SentimentScore sentiment,
            CatalystResult catalyst,
            Object marketQuality,
            Double rankScore,
            long ageMs,
            String decision,
            String reason,
            TradeDirection direction
    ) {
        String now = Instant.now().toString();

        String newsId = clean(news == null ? "" : news.getId());
        String ticker = clean(news == null ? "" : news.getTicker());
        String headline = clean(news == null ? "" : news.getHeadline());

        String positive = value(sentiment == null ? null : sentiment.positive);
        String negative = value(sentiment == null ? null : sentiment.negative);
        String neutral = value(sentiment == null ? null : sentiment.neutral);
        String netSentiment = value(sentiment == null ? null : sentiment.netSentiment());

        String catalystType = clean(catalyst == null ? "" : catalyst.type.toString());
        String catalystWeight = value(catalyst == null ? null : catalyst.weight);
        String catalystReason = clean(catalyst == null ? "" : catalyst.reason);

        String marketQualityValue = clean(marketQuality == null ? "" : marketQuality.toString());
        String rankScoreValue = value(rankScore);
        String directionValue = clean(direction == null ? TradeDirection.NO_TRADE.toString() : direction.toString());
        String decisionValue = clean(decision);
        String reasonValue = clean(reason);

        try (FileWriter writer = new FileWriter(FILE_NAME, true)) {

            writer.append(now).append(",");
            writer.append(newsId).append(",");
            writer.append(ticker).append(",");
            writer.append(headline).append(",");

            writer.append(positive).append(",");
            writer.append(negative).append(",");
            writer.append(neutral).append(",");
            writer.append(netSentiment).append(",");

            writer.append(catalystType).append(",");
            writer.append(catalystWeight).append(",");
            writer.append(catalystReason).append(",");

            writer.append(marketQualityValue).append(",");
            writer.append(rankScoreValue).append(",");
            writer.append(String.valueOf(ageMs)).append(",");

            writer.append(directionValue).append(",");
            writer.append(decisionValue).append(",");
            writer.append(reasonValue).append("\n");

            writer.flush();

            if (envBoolean("SIGNAL_JOURNAL_CONSOLE", false) || isImportantDecision(decisionValue)) {
                System.out.println(
                        "[SIGNAL] " +
                                now +
                                "," +
                                newsId +
                                "," +
                                ticker +
                                "," +
                                headline +
                                "," +
                                positive +
                                "," +
                                negative +
                                "," +
                                neutral +
                                "," +
                                netSentiment +
                                "," +
                                catalystType +
                                "," +
                                catalystWeight +
                                "," +
                                catalystReason +
                                "," +
                                marketQualityValue +
                                "," +
                                rankScoreValue +
                                "," +
                                ageMs +
                                "," +
                                directionValue +
                                "," +
                                decisionValue +
                                "," +
                                reasonValue
                );
            }

        } catch (IOException e) {
            System.err.println("Failed to write signal journal: " + e.getMessage());
        }
    }

    private boolean isImportantDecision(String decisionValue) {
        String value = decisionValue == null ? "" : decisionValue.toUpperCase();
        return value.contains("BUY") || value.contains("SELL") || value.contains("BLOCK");
    }

    private boolean envBoolean(String key, boolean fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()) || "yes".equalsIgnoreCase(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace(",", " ")
                .replace("\n", " ")
                .replace("\r", " ")
                .trim();
    }

    private String value(Double value) {
        if (value == null) {
            return "";
        }

        return String.valueOf(value);
    }
}
