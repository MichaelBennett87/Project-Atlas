package com.bot.model;

public class EntryContextSnapshot {

    public final String entryContextId;
    public final double probabilityTarget;
    public final double probabilityStop;
    public final double expectedValuePercent;
    public final double predictionConfidence;
    public final String predictionReason;
    public final String marketRegime;
    public final double rvol;
    public final double return3Bars;
    public final double vwapDistance;
    public final double sentimentNet;
    public final double catalystScore;
    public final double freshnessSeconds;

    public EntryContextSnapshot(
            String entryContextId,
            double probabilityTarget,
            double probabilityStop,
            double expectedValuePercent,
            double predictionConfidence,
            String predictionReason,
            String marketRegime,
            double rvol,
            double return3Bars,
            double vwapDistance,
            double sentimentNet,
            double catalystScore,
            double freshnessSeconds
    ) {
        this.entryContextId = clean(entryContextId);
        this.probabilityTarget = safe(probabilityTarget);
        this.probabilityStop = safe(probabilityStop);
        this.expectedValuePercent = safe(expectedValuePercent);
        this.predictionConfidence = safe(predictionConfidence);
        this.predictionReason = clean(predictionReason);
        this.marketRegime = clean(marketRegime);
        this.rvol = safe(rvol);
        this.return3Bars = safe(return3Bars);
        this.vwapDistance = safe(vwapDistance);
        this.sentimentNet = safe(sentimentNet);
        this.catalystScore = safe(catalystScore);
        this.freshnessSeconds = safe(freshnessSeconds);
    }

    public static EntryContextSnapshot none() {
        return new EntryContextSnapshot("", 0.0, 0.0, 0.0, 0.0, "", "UNKNOWN", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    public boolean hasContext() {
        return !entryContextId.isBlank();
    }

    private static double safe(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? 0.0 : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
