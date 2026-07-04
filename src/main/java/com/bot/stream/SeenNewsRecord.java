package com.bot.stream;

public class SeenNewsRecord {

    public final NewsFingerprint fingerprint;
    public final String firstSource;
    public final long firstSeenAt;
    public final long firstProviderTimestamp;
    public final String firstHeadline;

    public SeenNewsRecord(
            NewsFingerprint fingerprint,
            String firstSource,
            long firstSeenAt,
            long firstProviderTimestamp,
            String firstHeadline
    ) {
        this.fingerprint = fingerprint;
        this.firstSource = firstSource;
        this.firstSeenAt = firstSeenAt;
        this.firstProviderTimestamp = firstProviderTimestamp;
        this.firstHeadline = firstHeadline;
    }
}
