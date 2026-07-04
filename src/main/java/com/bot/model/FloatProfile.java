package com.bot.model;

public class FloatProfile {

    public final String ticker;
    public final long sharesFloat;
    public final double floatScore;
    public final boolean known;
    public final String category;
    public final String reason;

    public FloatProfile(
            String ticker,
            long sharesFloat,
            double floatScore,
            boolean known,
            String category,
            String reason
    ) {
        this.ticker = ticker;
        this.sharesFloat = sharesFloat;
        this.floatScore = floatScore;
        this.known = known;
        this.category = category;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "FloatProfile{" +
                "ticker='" + ticker + '\'' +
                ", sharesFloat=" + sharesFloat +
                ", floatScore=" + floatScore +
                ", known=" + known +
                ", category='" + category + '\'' +
                ", reason='" + reason + '\'' +
                '}';
    }
}