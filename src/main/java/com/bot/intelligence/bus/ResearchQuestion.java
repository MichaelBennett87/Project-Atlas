package com.bot.intelligence.bus;

/** A concrete research question the AI wants Polygon/AlphaVantage to answer. */
public final class ResearchQuestion {
    public final String symbol;
    public final String question;
    public final String requestHint;
    public final double confidence;
    public final double expectedInformationGain;
    public final long createdAtMs;

    public ResearchQuestion(String symbol, String question, String requestHint, double confidence, double expectedInformationGain) {
        this.symbol = symbol == null ? "" : symbol.trim().toUpperCase();
        this.question = question == null ? "" : question;
        this.requestHint = requestHint == null ? "" : requestHint;
        this.confidence = clamp(confidence);
        this.expectedInformationGain = clamp(expectedInformationGain);
        this.createdAtMs = System.currentTimeMillis();
    }

    public double priority() { return clamp(confidence * 0.45 + expectedInformationGain * 0.55); }
    private static double clamp(double v) { return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0, v)) : 0.0; }
    @Override public String toString() { return symbol + ":" + String.format(java.util.Locale.US, "%.3f", priority()) + ":" + requestHint; }
}
