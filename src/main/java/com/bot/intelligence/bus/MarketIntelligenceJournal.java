package com.bot.intelligence.bus;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class MarketIntelligenceJournal {
    private final Path path;
    private volatile boolean headerWritten = false;

    public MarketIntelligenceJournal() {
        this(Path.of(System.getenv().getOrDefault("MARKET_INTELLIGENCE_BUS_JOURNAL", "logs/market_intelligence_bus.csv")));
    }

    public MarketIntelligenceJournal(Path path) {
        this.path = path;
    }

    public synchronized void record(MarketIntelligenceSignal signal, boolean duplicate) {
        if (signal == null || path == null) return;
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            boolean exists = Files.exists(path) && Files.size(path) > 0;
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (!exists && !headerWritten) {
                    writer.write("receivedAt,provider,type,ticker,confidence,priority,duplicate,providerTimestamp,headline");
                    writer.newLine();
                    headerWritten = true;
                }
                writer.write(signal.getReceivedAtMs() + "," +
                        csv(signal.getProvider()) + "," +
                        csv(String.valueOf(signal.getType())) + "," +
                        csv(signal.getTicker()) + "," +
                        signal.getConfidence() + "," +
                        signal.getPriority() + "," +
                        duplicate + "," +
                        signal.getProviderTimestampMs() + "," +
                        csv(signal.getHeadline()));
                writer.newLine();
            }
        } catch (Exception e) {
            System.out.println("MARKET INTELLIGENCE BUS JOURNAL WARNING: " + e.getMessage());
        }
    }

    private static String csv(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
