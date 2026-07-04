package com.bot.intelligence;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Computes a richer technical feature vector from historical/intraday bars.
 * This fills the old world-model blind spot where technical=0.00000 even when
 * liquidity/parabolic/news evidence exists. It is deliberately dependency-free
 * so it can run both during nightly replay and from simple CSV repositories.
 */
public final class TechnicalIntelligenceEngine {
    private final Path journal = Path.of(env("TECHNICAL_FEATURE_JOURNAL", "logs/technical_feature_vectors.csv"));
    private final HistoricalMarketDataRepository repository = new HistoricalMarketDataRepository();

    public RunResult runFromHistoricalRepository() {
        int maxFiles = intEnv("TECHNICAL_ENGINE_MAX_FILES", 300);
        int maxRows = intEnv("TECHNICAL_ENGINE_MAX_ROWS_PER_FILE", 20_000);
        Map<String, List<HistoricalMarketDataRepository.HistoricalBar>> byTicker = new LinkedHashMap<>();
        int files = 0;
        for (Path file : repository.csvFiles()) {
            if (++files > maxFiles) break;
            for (HistoricalMarketDataRepository.HistoricalBar bar : repository.loadBars(file, maxRows)) {
                byTicker.computeIfAbsent(bar.ticker, k -> new ArrayList<>()).add(bar);
            }
        }
        List<TechnicalFeatureVector> vectors = new ArrayList<>();
        MultiTimeframeIntelligenceEngine mtf = new MultiTimeframeIntelligenceEngine();
        for (Map.Entry<String, List<HistoricalMarketDataRepository.HistoricalBar>> e : byTicker.entrySet()) {
            e.getValue().sort(Comparator.comparing(b -> b.timestamp == null ? "" : b.timestamp));
            vectors.addAll(mtf.compute(e.getKey(), e.getValue()));
        }
        write(vectors);
        System.out.println("TECHNICAL INTELLIGENCE ENGINE COMPLETE: tickers=" + byTicker.size() + " vectors=" + vectors.size() + " journal=" + journal);
        return new RunResult(byTicker.size(), vectors.size(), journal.toString());
    }

    public TechnicalFeatureVector compute(String ticker, String timeframe, List<HistoricalMarketDataRepository.HistoricalBar> bars) {
        if (bars == null || bars.isEmpty()) return empty(ticker, timeframe);
        List<HistoricalMarketDataRepository.HistoricalBar> b = new ArrayList<>(bars);
        b.sort(Comparator.comparing(x -> x.timestamp == null ? "" : x.timestamp));
        int n = b.size();
        double firstOpen = positive(b.get(0).open, b.get(0).close);
        double lastClose = b.get(n - 1).close;
        double high = 0, low = Double.MAX_VALUE, totalVolume = 0, pv = 0;
        for (HistoricalMarketDataRepository.HistoricalBar x : b) {
            high = Math.max(high, positive(x.high, x.close));
            low = Math.min(low, positive(x.low, x.close));
            totalVolume += Math.max(0, x.volume);
            pv += Math.max(0, x.volume) * x.close;
        }
        if (low == Double.MAX_VALUE) low = lastClose;
        double vwap = totalVolume <= 0 ? lastClose : pv / totalVolume;
        double emaFast = ema(b, Math.min(9, Math.max(2, n / 4)));
        double emaSlow = ema(b, Math.min(21, Math.max(3, n / 2)));
        double atr = atr(b, Math.min(14, Math.max(2, n - 1)));
        double avgVol = avgVolume(b, 0, Math.max(1, n - 1));
        double recentVol = avgVolume(b, Math.max(0, n - Math.max(3, n / 5)), n);
        double priorClose = n > 1 ? b.get(n - 2).close : firstOpen;
        double gapPct = pct(b.get(0).open - priorClose, priorClose);
        double returnPct = pct(lastClose - firstOpen, firstOpen);
        double vwapDistance = pct(lastClose - vwap, vwap);
        double emaTrend = clamp01(0.5 + pct(emaFast - emaSlow, lastClose) / 10.0);
        double rsi = rsi(b, Math.min(14, Math.max(2, n - 1)));
        double atrPct = pct(atr, lastClose);
        double rv = avgVol <= 0 ? 0 : recentVol / avgVol;
        double volAccel = volumeAcceleration(b);
        double rangePct = pct(high - low, firstOpen);
        double momentumSlope = slope(b);
        double pullback = high <= 0 ? 0 : pct(high - lastClose, high);
        double breakout = clamp01((returnPct / 8.0) + (vwapDistance / 8.0) + Math.min(1.0, rv / 10.0) * 0.35);
        double meanReversion = clamp01((Math.max(0, -vwapDistance) / 8.0) + (rsi < 35 ? (35 - rsi) / 35.0 : 0));
        double parabolic = clamp01((rangePct / 25.0) + Math.min(1.0, rv / 12.0) * 0.45 + Math.max(0, momentumSlope) * 0.4);
        double technical = clamp01(0.22 * breakout + 0.18 * emaTrend + 0.16 * clamp01((rsi - 35) / 35.0)
                + 0.18 * Math.min(1.0, rv / 8.0) + 0.16 * parabolic + 0.10 * clamp01(0.5 + vwapDistance / 10.0));
        return new TechnicalFeatureVector(ticker, timeframe, n, lastClose, returnPct, gapPct, vwapDistance, emaFast,
                emaSlow, emaTrend, rsi, atrPct, rv, volAccel, rangePct, momentumSlope, pullback, breakout,
                meanReversion, parabolic, technical);
    }

    public void write(List<TechnicalFeatureVector> vectors) {
        if (vectors == null || vectors.isEmpty()) return;
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            boolean exists = Files.exists(journal) && Files.size(journal) > 0;
            try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (!exists) { w.write("generatedAt," + vectors.get(0).csvHeader()); w.newLine(); }
                String now = Instant.now().toString();
                for (TechnicalFeatureVector v : vectors) { w.write('"' + now + "\"," + v.toCsv()); w.newLine(); }
            }
        } catch (Exception e) {
            System.out.println("TECHNICAL FEATURE JOURNAL FAILED: " + e.getMessage());
        }
    }

    private static TechnicalFeatureVector empty(String ticker, String timeframe) { return new TechnicalFeatureVector(ticker, timeframe, 0,0,0,0,0,0,0,0.5,50,0,0,0,0,0,0,0,0,0,0); }
    private static double ema(List<HistoricalMarketDataRepository.HistoricalBar> b, int period) { double k = 2.0 / (period + 1.0); double e = b.get(0).close; for (HistoricalMarketDataRepository.HistoricalBar x : b) e = x.close * k + e * (1 - k); return e; }
    private static double atr(List<HistoricalMarketDataRepository.HistoricalBar> b, int period) { int start = Math.max(1, b.size() - period); double sum = 0; int c = 0; for (int i = start; i < b.size(); i++) { HistoricalMarketDataRepository.HistoricalBar x = b.get(i); double prev = b.get(i-1).close; double tr = Math.max(positive(x.high,x.close)-positive(x.low,x.close), Math.max(Math.abs(positive(x.high,x.close)-prev), Math.abs(positive(x.low,x.close)-prev))); sum += tr; c++; } return c == 0 ? 0 : sum / c; }
    private static double avgVolume(List<HistoricalMarketDataRepository.HistoricalBar> b, int from, int to) { double s=0; int c=0; for(int i=Math.max(0,from); i<Math.min(b.size(),to); i++){ s+=Math.max(0,b.get(i).volume); c++; } return c==0?0:s/c; }
    private static double rsi(List<HistoricalMarketDataRepository.HistoricalBar> b, int period) { double g=0,l=0; int start=Math.max(1,b.size()-period); for(int i=start;i<b.size();i++){ double d=b.get(i).close-b.get(i-1).close; if(d>=0) g+=d; else l-=d; } if(g+l==0) return 50; if(l==0) return 100; double rs=g/l; return 100 - (100/(1+rs)); }
    private static double volumeAcceleration(List<HistoricalMarketDataRepository.HistoricalBar> b) { int n=b.size(); if(n<6) return 0; double recent=avgVolume(b,n/2,n); double prior=avgVolume(b,0,n/2); return prior<=0?0:recent/prior; }
    private static double slope(List<HistoricalMarketDataRepository.HistoricalBar> b) { int n=b.size(); if(n<2) return 0; double first=b.get(Math.max(0,n-5)).close; double last=b.get(n-1).close; return clamp(pct(last-first, first)/10.0, -1, 1); }
    private static double positive(double v, double fallback) { return v > 0 ? v : fallback; }
    private static double pct(double diff, double base) { return base == 0 ? 0 : (diff / base) * 100.0; }
    private static double clamp01(double v) { return clamp(v, 0, 1); }
    private static double clamp(double v, double min, double max) { if(!Double.isFinite(v)) return min; return Math.max(min, Math.min(max, v)); }
    private static String env(String k, String f) { String v=System.getenv(k); return v==null||v.isBlank()?f:v.trim(); }
    private static int intEnv(String k, int f) { try { String v=System.getenv(k); return v==null||v.isBlank()?f:Integer.parseInt(v.trim()); } catch(Exception e){ return f; } }
    public static final class RunResult { public final int tickers, vectors; public final String journal; RunResult(int tickers,int vectors,String journal){this.tickers=tickers;this.vectors=vectors;this.journal=journal;} public String summary(){ return "tickers="+tickers+" vectors="+vectors+" journal="+journal; } }
}
