package com.bot.intelligence.historical;

import java.nio.file.Path;
import java.time.Instant;

/** Result of a historical REST/cache request. */
public final class HistoricalDataResponse {
    public final HistoricalDataRequest request;
    public final boolean success;
    public final String provider;
    public final String message;
    public final Path outputPath;
    public final int rows;
    public final long elapsedMs;
    public final Instant completedAt;

    public HistoricalDataResponse(HistoricalDataRequest request, boolean success, String provider,
                                  String message, Path outputPath, int rows, long elapsedMs) {
        this.request = request;
        this.success = success;
        this.provider = provider == null ? "UNKNOWN" : provider;
        this.message = message == null ? "" : message;
        this.outputPath = outputPath;
        this.rows = rows;
        this.elapsedMs = elapsedMs;
        this.completedAt = Instant.now();
    }

    public String summary() {
        return "success=" + success + " provider=" + provider + " rows=" + rows +
                " output=" + (outputPath == null ? "" : outputPath) + " message=" + message;
    }
}
