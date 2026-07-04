package com.bot.intelligence;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Generates many labeled what-if entries from historical bars for overnight training. */
public final class SyntheticTrainingGenerator {
    private final HistoricalMarketDataRepository repository = new HistoricalMarketDataRepository();
    private final Path out = Path.of(env("SYNTHETIC_TRAINING_JOURNAL", "logs/synthetic_training_examples.csv"));

    public Result generate() {
        int maxFiles = intEnv("SYNTHETIC_TRAINING_MAX_FILES", 200);
        int maxRows = intEnv("SYNTHETIC_TRAINING_MAX_ROWS_PER_FILE", 25_000);
        int step = Math.max(1, intEnv("SYNTHETIC_TRAINING_STEP_BARS", 5));
        int horizon = Math.max(3, intEnv("SYNTHETIC_TRAINING_HORIZON_BARS", 30));
        int files = 0, examples = 0;
        try {
            Path parent = out.getParent(); if (parent != null) Files.createDirectories(parent);
            boolean exists = Files.exists(out) && Files.size(out) > 0;
            try (BufferedWriter w = Files.newBufferedWriter(out, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (!exists) { w.write("generatedAt,ticker,timestamp,entry,side,horizonBars,mfePct,maePct,finalPct,label"); w.newLine(); }
                for (Path file : repository.csvFiles()) {
                    if (++files > maxFiles) break;
                    List<HistoricalMarketDataRepository.HistoricalBar> bars = repository.loadBars(file, maxRows);
                    bars.sort(Comparator.comparing(b -> b.timestamp == null ? "" : b.timestamp));
                    for (int i = 20; i + horizon < bars.size(); i += step) {
                        HistoricalMarketDataRepository.HistoricalBar e = bars.get(i);
                        examples += writeExample(w, e, bars.subList(i + 1, i + 1 + horizon), "LONG", horizon);
                        examples += writeExample(w, e, bars.subList(i + 1, i + 1 + horizon), "SHORT", horizon);
                    }
                }
            }
        } catch (Exception e) { System.out.println("SYNTHETIC TRAINING GENERATOR FAILED: " + e.getMessage()); }
        System.out.println("SYNTHETIC TRAINING GENERATED: files=" + files + " examples=" + examples + " journal=" + out);
        return new Result(files, examples, out.toString());
    }

    private int writeExample(BufferedWriter w, HistoricalMarketDataRepository.HistoricalBar entry, List<HistoricalMarketDataRepository.HistoricalBar> future, String side, int horizon) throws Exception {
        if (entry.close <= 0 || future.isEmpty()) return 0;
        double mfe = -999, mae = 999;
        for (HistoricalMarketDataRepository.HistoricalBar b : future) {
            double high = side.equals("LONG") ? pct(b.high - entry.close, entry.close) : pct(entry.close - b.low, entry.close);
            double low = side.equals("LONG") ? pct(b.low - entry.close, entry.close) : pct(entry.close - b.high, entry.close);
            mfe = Math.max(mfe, high); mae = Math.min(mae, low);
        }
        double finalPct = side.equals("LONG") ? pct(future.get(future.size()-1).close - entry.close, entry.close) : pct(entry.close - future.get(future.size()-1).close, entry.close);
        String label = mfe >= 3.0 && mae > -2.5 ? "HIGH_QUALITY" : finalPct > 0 ? "WIN" : "LOSS";
        w.write(String.join(",", csv(Instant.now().toString()), csv(entry.ticker), csv(entry.timestamp), d(entry.close), csv(side), String.valueOf(horizon), d(mfe), d(mae), d(finalPct), csv(label)));
        w.newLine(); return 1;
    }
    private static double pct(double diff,double base){return base==0?0:(diff/base)*100.0;}
    private static String d(double v){return String.format(Locale.US,"%.6f",Double.isFinite(v)?v:0);}
    private static String csv(String v){String s=v==null?"":v.replace("\n"," ").replace("\r"," ");return '"'+s.replace("\"","\"\"")+'"';}
    private static String env(String k,String f){String v=System.getenv(k);return v==null||v.isBlank()?f:v.trim();}
    private static int intEnv(String k,int f){try{String v=System.getenv(k);return v==null||v.isBlank()?f:Integer.parseInt(v.trim());}catch(Exception e){return f;}}
    public static final class Result { public final int files, examples; public final String journal; Result(int f,int e,String j){files=f;examples=e;journal=j;} public String summary(){return "files="+files+" examples="+examples+" journal="+journal;} }
}
