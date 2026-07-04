package com.bot.intelligence;

import com.bot.master.StrategySignal;
import com.bot.master.StrategyContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Records high-quality candidates that were not entered so offline evolution can learn from missed moves. */
public class MissedTradeJournal {
    private final Path path;
    private boolean headerWritten;
    private final Map<String, Long> recent = new ConcurrentHashMap<>();
    private final long suppressMs = envLong("MISSED_TRADE_JOURNAL_SUPPRESS_MS", 90_000L);
    private final double minConfidence = envDouble("MISSED_TRADE_MIN_CONFIDENCE", 0.50);

    public MissedTradeJournal() {
        this(Path.of(System.getenv().getOrDefault("MISSED_TRADE_JOURNAL_PATH", "logs/missed_trades.csv")));
    }
    public MissedTradeJournal(Path path) { this.path = path; this.headerWritten = Files.exists(path); }

    public synchronized void record(StrategyContext context, StrategySignal signal, ProbabilityPrediction prediction, String blockReason) {
        if (context == null || signal == null || signal.getConfidence() < minConfidence) return;
        String ticker = context.getTicker() == null ? signal.getTicker() : context.getTicker().trim().toUpperCase();
        String key = ticker + "|" + signal.getStrategyName() + "|" + blockReason;
        long now = System.currentTimeMillis();
        Long prev = recent.put(key, now);
        if (prev != null && now - prev < suppressMs) return;
        recent.entrySet().removeIf(e -> now - e.getValue() > suppressMs * 4);
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (!headerWritten) {
                Files.writeString(path, String.join(",", "timestamp","ticker","strategy","direction","confidence","expectedMovePercent","priorityScore","pTarget","pStop","ev","barCount","lastPrice","reason","blockReason") + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                headerWritten = true;
            }
            int bars = context.getBars() == null ? 0 : context.getBars().size();
            String row = String.join(",",
                    esc(Instant.ofEpochMilli(now).toString()), esc(ticker), esc(signal.getStrategyName()), esc(String.valueOf(signal.getDirection())),
                    val(signal.getConfidence()), val(signal.getExpectedMovePercent()), val(signal.priorityScore()),
                    val(prediction == null ? 0.0 : prediction.getProbabilityHitProfitTarget()),
                    val(prediction == null ? 0.0 : prediction.getProbabilityHitStopLoss()),
                    val(prediction == null ? 0.0 : prediction.getExpectedValuePercent()),
                    Integer.toString(bars), val(context.getLastPrice()), esc(signal.getReason()), esc(blockReason));
            Files.writeString(path, row + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("MISSED TRADE JOURNAL WRITE FAILED: " + e.getMessage());
        }
    }
    private static String val(double d){return Double.isNaN(d)||Double.isInfinite(d)?"0.0":Double.toString(d);}    
    private static String esc(String v){ if(v==null)return""; String s=v.replace('\n',' ').replace('\r',' '); return s.contains(",")||s.contains("\"")?"\""+s.replace("\"","\"\"")+"\"":s; }
    private static long envLong(String key,long fallback){try{String v=System.getenv(key);return v==null||v.isBlank()?fallback:Long.parseLong(v.trim());}catch(Exception e){return fallback;}}
    private static double envDouble(String key,double fallback){try{String v=System.getenv(key);return v==null||v.isBlank()?fallback:Double.parseDouble(v.trim());}catch(Exception e){return fallback;}}
}
