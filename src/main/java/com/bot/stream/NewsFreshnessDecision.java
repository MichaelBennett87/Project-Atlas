package com.bot.stream;

public class NewsFreshnessDecision {

    public final boolean allowed;
    public final boolean duplicate;
    public final String category;
    public final String reason;
    public final long providerAgeMs;
    public final long botFirstSeenAgeMs;
    public final long sourceLagMs;
    public final String firstSource;

    public NewsFreshnessDecision(
            boolean allowed,
            boolean duplicate,
            String category,
            String reason,
            long providerAgeMs,
            long botFirstSeenAgeMs,
            long sourceLagMs,
            String firstSource
    ) {
        this.allowed = allowed;
        this.duplicate = duplicate;
        this.category = category;
        this.reason = reason;
        this.providerAgeMs = providerAgeMs;
        this.botFirstSeenAgeMs = botFirstSeenAgeMs;
        this.sourceLagMs = sourceLagMs;
        this.firstSource = firstSource;
    }

    @Override
    public String toString() {
        return "NewsFreshnessDecision{" +
                "allowed=" + allowed +
                ", duplicate=" + duplicate +
                ", category='" + category + '\'' +
                ", reason='" + reason + '\'' +
                ", providerAgeMs=" + providerAgeMs +
                ", botFirstSeenAgeMs=" + botFirstSeenAgeMs +
                ", sourceLagMs=" + sourceLagMs +
                ", firstSource='" + firstSource + '\'' +
                '}';
    }
}
