package com.bot.stream;

import com.bot.model.NewsEvent;

import java.util.Locale;
import java.util.Objects;

public class NewsFingerprint {

    private final String ticker;
    private final String normalizedHeadline;

    public NewsFingerprint(
            String ticker,
            String normalizedHeadline
    ) {
        this.ticker = normalizeTicker(ticker);
        this.normalizedHeadline = normalizeHeadline(normalizedHeadline);
    }

    public static NewsFingerprint fromNews(
            NewsEvent news
    ) {
        if (news == null) {
            return new NewsFingerprint("", "");
        }

        return new NewsFingerprint(
                news.getTicker(),
                news.getHeadline()
        );
    }

    public String ticker() {
        return ticker;
    }

    public String normalizedHeadline() {
        return normalizedHeadline;
    }

    private static String normalizeTicker(
            String value
    ) {
        return value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeHeadline(
            String value
    ) {
        if (value == null) {
            return "";
        }

        String normalized =
                value.toLowerCase(Locale.ROOT)
                        .replaceAll("&#39;", "'")
                        .replaceAll("&amp;", "and")
                        .replaceAll("[^a-z0-9 ]", " ")
                        .replaceAll("\\s+", " ")
                        .trim();

        if (normalized.length() <= 160) {
            return normalized;
        }

        return normalized.substring(0, 160);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof NewsFingerprint)) {
            return false;
        }

        NewsFingerprint that =
                (NewsFingerprint) other;

        return Objects.equals(ticker, that.ticker) &&
                Objects.equals(normalizedHeadline, that.normalizedHeadline);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ticker, normalizedHeadline);
    }

    @Override
    public String toString() {
        return ticker + "|" + normalizedHeadline;
    }
}
