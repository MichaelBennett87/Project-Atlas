package com.bot.model;

public class NewsEvent {

    private String id;
    private String ticker;
    private String headline;
    private String content;
    private long timestamp;

    private String source;
    private long providerTimestamp;
    private long botFirstSeenAt;
    private long sourceLagMs;
    private boolean staleRejected;
    private String freshnessReason;

    private double sentimentScore;
    private double catalystScore;

    public NewsEvent() {
        this.botFirstSeenAt = System.currentTimeMillis();
        this.source = "UNKNOWN";
    }

    public NewsEvent(
            String id,
            String ticker,
            String headline,
            String content,
            long timestamp
    ) {
        this.id = id;
        this.ticker = ticker;
        this.headline = headline;
        this.content = content;
        this.timestamp = timestamp;
        this.providerTimestamp = timestamp;
        this.botFirstSeenAt = System.currentTimeMillis();
        this.source = "UNKNOWN";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public String getHeadline() {
        return headline;
    }

    public void setHeadline(String headline) {
        this.headline = headline;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
        if (providerTimestamp <= 0) {
            this.providerTimestamp = timestamp;
        }
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = normalizeSource(source);
    }

    public boolean hasKnownSource() {
        return source != null && !source.isBlank() && !"UNKNOWN".equalsIgnoreCase(source.trim());
    }

    private static String normalizeSource(String source) {
        if (source == null || source.trim().isBlank()) {
            return "UNKNOWN";
        }

        String normalized = source.trim().toUpperCase().replace(' ', '_');
        if ("ALPACA".equals(normalized)) {
            return "ALPACA_NEWS";
        }
        if ("BENZINGA".equals(normalized) || "BENZINGA_WS".equals(normalized)) {
            return "BENZINGA_DIRECT";
        }
        if ("BENZINGA_PR".equals(normalized) || "PRESS_RELEASE".equals(normalized)) {
            return "BENZINGA_PRESS_RELEASE_WS";
        }
        return normalized;
    }

    public long getProviderTimestamp() {
        return providerTimestamp;
    }

    public void setProviderTimestamp(long providerTimestamp) {
        this.providerTimestamp = providerTimestamp;
    }

    public long getBotFirstSeenAt() {
        return botFirstSeenAt;
    }

    public void setBotFirstSeenAt(long botFirstSeenAt) {
        this.botFirstSeenAt = botFirstSeenAt;
    }

    public long getSourceLagMs() {
        return sourceLagMs;
    }

    public void setSourceLagMs(long sourceLagMs) {
        this.sourceLagMs = sourceLagMs;
    }

    public boolean isStaleRejected() {
        return staleRejected;
    }

    public void setStaleRejected(boolean staleRejected) {
        this.staleRejected = staleRejected;
    }

    public String getFreshnessReason() {
        return freshnessReason;
    }

    public void setFreshnessReason(String freshnessReason) {
        this.freshnessReason = freshnessReason;
    }

    public double getSentimentScore() {
        return sentimentScore;
    }

    public void setSentimentScore(double sentimentScore) {
        this.sentimentScore = sentimentScore;
    }

    public double getCatalystScore() {
        return catalystScore;
    }

    public void setCatalystScore(double catalystScore) {
        this.catalystScore = catalystScore;
    }

    public String fullText() {
        return (headline == null ? "" : headline)
                + " "
                + (content == null ? "" : content);
    }

    @Override
    public String toString() {
        return "NewsEvent{" +
                "id='" + id + '\'' +
                ", ticker='" + ticker + '\'' +
                ", headline='" + headline + '\'' +
                ", source='" + source + '\'' +
                ", timestamp=" + timestamp +
                ", providerTimestamp=" + providerTimestamp +
                ", botFirstSeenAt=" + botFirstSeenAt +
                ", sourceLagMs=" + sourceLagMs +
                ", staleRejected=" + staleRejected +
                ", freshnessReason='" + freshnessReason + '\'' +
                ", sentimentScore=" + sentimentScore +
                ", catalystScore=" + catalystScore +
                '}';
    }
}
