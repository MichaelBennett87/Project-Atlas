package com.bot.intelligence;

import java.util.Locale;

/** Immutable normalized technical state for one ticker/timeframe. */
public final class TechnicalFeatureVector {
    public final String ticker;
    public final String timeframe;
    public final int bars;
    public final double lastClose;
    public final double returnPct;
    public final double gapPct;
    public final double vwapDistancePct;
    public final double emaFast;
    public final double emaSlow;
    public final double emaTrendScore;
    public final double rsi14;
    public final double atrPct;
    public final double relativeVolume;
    public final double volumeAcceleration;
    public final double intradayRangePct;
    public final double momentumSlope;
    public final double pullbackDepthPct;
    public final double breakoutScore;
    public final double meanReversionScore;
    public final double parabolicScore;
    public final double technicalScore;

    public TechnicalFeatureVector(String ticker, String timeframe, int bars, double lastClose, double returnPct,
                                  double gapPct, double vwapDistancePct, double emaFast, double emaSlow,
                                  double emaTrendScore, double rsi14, double atrPct, double relativeVolume,
                                  double volumeAcceleration, double intradayRangePct, double momentumSlope,
                                  double pullbackDepthPct, double breakoutScore, double meanReversionScore,
                                  double parabolicScore, double technicalScore) {
        this.ticker = ticker == null ? "" : ticker.toUpperCase(Locale.ROOT);
        this.timeframe = timeframe == null ? "" : timeframe;
        this.bars = bars;
        this.lastClose = lastClose;
        this.returnPct = returnPct;
        this.gapPct = gapPct;
        this.vwapDistancePct = vwapDistancePct;
        this.emaFast = emaFast;
        this.emaSlow = emaSlow;
        this.emaTrendScore = emaTrendScore;
        this.rsi14 = rsi14;
        this.atrPct = atrPct;
        this.relativeVolume = relativeVolume;
        this.volumeAcceleration = volumeAcceleration;
        this.intradayRangePct = intradayRangePct;
        this.momentumSlope = momentumSlope;
        this.pullbackDepthPct = pullbackDepthPct;
        this.breakoutScore = breakoutScore;
        this.meanReversionScore = meanReversionScore;
        this.parabolicScore = parabolicScore;
        this.technicalScore = technicalScore;
    }

    public String csvHeader() {
        return "ticker,timeframe,bars,lastClose,returnPct,gapPct,vwapDistancePct,emaFast,emaSlow,emaTrendScore,rsi14,atrPct,relativeVolume,volumeAcceleration,intradayRangePct,momentumSlope,pullbackDepthPct,breakoutScore,meanReversionScore,parabolicScore,technicalScore";
    }

    public String toCsv() {
        return String.join(",", q(ticker), q(timeframe), String.valueOf(bars), d(lastClose), d(returnPct), d(gapPct),
                d(vwapDistancePct), d(emaFast), d(emaSlow), d(emaTrendScore), d(rsi14), d(atrPct), d(relativeVolume),
                d(volumeAcceleration), d(intradayRangePct), d(momentumSlope), d(pullbackDepthPct), d(breakoutScore),
                d(meanReversionScore), d(parabolicScore), d(technicalScore));
    }

    private static String d(double v) { return String.format(Locale.US, "%.6f", Double.isFinite(v) ? v : 0.0); }
    private static String q(String s) { return '"' + (s == null ? "" : s.replace("\"", "\"\"") ) + '"'; }
}
