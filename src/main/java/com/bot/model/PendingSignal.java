package com.bot.model;

public class PendingSignal {

    public final NewsOpportunity opportunity;
    public final long createdAt;
    public final long expiresAt;

    public PendingSignal(
            NewsOpportunity opportunity,
            long createdAt,
            long expiresAt
    ) {
        this.opportunity = opportunity;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }
}