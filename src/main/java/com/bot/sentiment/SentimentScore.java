package com.bot.sentiment;

public class SentimentScore {

    public final double positive;
    public final double negative;
    public final double neutral;

    public SentimentScore(double positive, double negative, double neutral) {
        this.positive = positive;
        this.negative = negative;
        this.neutral = neutral;
    }

    public double netSentiment() {
        return positive - negative;
    }

    @Override
    public String toString() {
        return "SentimentScore{" +
                "positive=" + positive +
                ", negative=" + negative +
                ", neutral=" + neutral +
                ", net=" + netSentiment() +
                '}';
    }
}