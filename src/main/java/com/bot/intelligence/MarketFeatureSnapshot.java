package com.bot.intelligence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class MarketFeatureSnapshot {

    public final String ticker;
    public final long timestamp;
    public final int barCount;
    public final double lastPrice;
    public final double return1Bar;
    public final double return3Bars;
    public final double return5Bars;
    public final double return10Bars;
    public final double dropFromHigh20;
    public final double bounceFromLow20;
    public final double rvol5;
    public final double rvol20;
    public final double greenVolumeRatio10;
    public final double atrPercent14;
    public final double rsi14;
    public final double vwap30;
    public final double vwapDistance;
    public final boolean bullishBreak;
    public final boolean reclaimedVwap;
    public final boolean failedBreakdown;
    public final boolean higherLows3;
    public final boolean noFreshLow3;
    public final double sentimentNet;
    public final double sentimentPositive;
    public final double sentimentNegative;
    public final double catalystScore;
    public final double freshnessSeconds;
    public final String newsSource;
    public final String headline;

    public MarketFeatureSnapshot(
            String ticker,
            long timestamp,
            int barCount,
            double lastPrice,
            double return1Bar,
            double return3Bars,
            double return5Bars,
            double return10Bars,
            double dropFromHigh20,
            double bounceFromLow20,
            double rvol5,
            double rvol20,
            double greenVolumeRatio10,
            double atrPercent14,
            double rsi14,
            double vwap30,
            double vwapDistance,
            boolean bullishBreak,
            boolean reclaimedVwap,
            boolean failedBreakdown,
            boolean higherLows3,
            boolean noFreshLow3,
            double sentimentNet,
            double sentimentPositive,
            double sentimentNegative,
            double catalystScore,
            double freshnessSeconds,
            String newsSource,
            String headline
    ) {
        this.ticker = ticker == null ? "UNKNOWN" : ticker.trim().toUpperCase();
        this.timestamp = timestamp;
        this.barCount = barCount;
        this.lastPrice = safe(lastPrice);
        this.return1Bar = safe(return1Bar);
        this.return3Bars = safe(return3Bars);
        this.return5Bars = safe(return5Bars);
        this.return10Bars = safe(return10Bars);
        this.dropFromHigh20 = safe(dropFromHigh20);
        this.bounceFromLow20 = safe(bounceFromLow20);
        this.rvol5 = safe(rvol5);
        this.rvol20 = safe(rvol20);
        this.greenVolumeRatio10 = safe(greenVolumeRatio10);
        this.atrPercent14 = safe(atrPercent14);
        this.rsi14 = safe(rsi14);
        this.vwap30 = safe(vwap30);
        this.vwapDistance = safe(vwapDistance);
        this.bullishBreak = bullishBreak;
        this.reclaimedVwap = reclaimedVwap;
        this.failedBreakdown = failedBreakdown;
        this.higherLows3 = higherLows3;
        this.noFreshLow3 = noFreshLow3;
        this.sentimentNet = safe(sentimentNet);
        this.sentimentPositive = safe(sentimentPositive);
        this.sentimentNegative = safe(sentimentNegative);
        this.catalystScore = safe(catalystScore);
        this.freshnessSeconds = safe(freshnessSeconds);
        this.newsSource = newsSource == null ? "" : newsSource.replace(',', ' ').trim();
        this.headline = headline == null ? "" : headline.replace(',', ' ').replace('\n', ' ').trim();
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", Instant.ofEpochMilli(timestamp).toString());
        map.put("ticker", ticker);
        map.put("barCount", barCount);
        map.put("lastPrice", lastPrice);
        map.put("return1Bar", return1Bar);
        map.put("return3Bars", return3Bars);
        map.put("return5Bars", return5Bars);
        map.put("return10Bars", return10Bars);
        map.put("dropFromHigh20", dropFromHigh20);
        map.put("bounceFromLow20", bounceFromLow20);
        map.put("rvol5", rvol5);
        map.put("rvol20", rvol20);
        map.put("greenVolumeRatio10", greenVolumeRatio10);
        map.put("atrPercent14", atrPercent14);
        map.put("rsi14", rsi14);
        map.put("vwapDistance", vwapDistance);
        map.put("bullishBreak", bullishBreak);
        map.put("reclaimedVwap", reclaimedVwap);
        map.put("failedBreakdown", failedBreakdown);
        map.put("higherLows3", higherLows3);
        map.put("noFreshLow3", noFreshLow3);
        map.put("sentimentNet", sentimentNet);
        map.put("sentimentPositive", sentimentPositive);
        map.put("sentimentNegative", sentimentNegative);
        map.put("catalystScore", catalystScore);
        map.put("freshnessSeconds", freshnessSeconds);
        map.put("newsSource", newsSource);
        map.put("headline", headline);
        return map;
    }

    public String csvHeader() {
        return String.join(",", asMap().keySet());
    }

    public String csvRow() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object value : asMap().values()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(escapeCsv(String.valueOf(value)));
        }
        return sb.toString();
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static double safe(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return value;
    }
}
