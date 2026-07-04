package com.bot.strategy;

import java.util.List;
import java.util.Map;

public class SectorTickerMapper {

    private final Map<String, String> tickerToSector =
            Map.ofEntries(
                    Map.entry("SMCI", "AI_INFRASTRUCTURE"),
                    Map.entry("NVDA", "AI_INFRASTRUCTURE"),
                    Map.entry("AMD", "AI_INFRASTRUCTURE"),
                    Map.entry("AVGO", "AI_INFRASTRUCTURE"),
                    Map.entry("ARM", "AI_INFRASTRUCTURE"),
                    Map.entry("CRWV", "AI_INFRASTRUCTURE"),
                    Map.entry("NBIS", "AI_INFRASTRUCTURE"),

                    Map.entry("AAPL", "MEGA_CAP_TECH"),
                    Map.entry("MSFT", "MEGA_CAP_TECH"),
                    Map.entry("GOOGL", "MEGA_CAP_TECH"),
                    Map.entry("META", "MEGA_CAP_TECH"),
                    Map.entry("AMZN", "MEGA_CAP_TECH"),
                    Map.entry("TSLA", "MEGA_CAP_TECH"),

                    Map.entry("JPM", "BANKS"),
                    Map.entry("BAC", "BANKS"),
                    Map.entry("WFC", "BANKS"),
                    Map.entry("C", "BANKS"),
                    Map.entry("GS", "BANKS"),

                    Map.entry("XOM", "ENERGY"),
                    Map.entry("CVX", "ENERGY"),
                    Map.entry("OXY", "ENERGY"),
                    Map.entry("SLB", "ENERGY"),

                    Map.entry("PFE", "LARGE_CAP_HEALTHCARE"),
                    Map.entry("MRK", "LARGE_CAP_HEALTHCARE"),
                    Map.entry("JNJ", "LARGE_CAP_HEALTHCARE"),
                    Map.entry("LLY", "LARGE_CAP_HEALTHCARE"),
                    Map.entry("UNH", "LARGE_CAP_HEALTHCARE")
            );

    private final Map<String, List<String>> sectorBaskets =
            Map.of(
                    "AI_INFRASTRUCTURE",
                    List.of("SMCI", "NVDA", "AMD", "AVGO", "ARM", "CRWV", "NBIS"),

                    "MEGA_CAP_TECH",
                    List.of("AAPL", "MSFT", "GOOGL", "META", "AMZN", "TSLA"),

                    "BANKS",
                    List.of("JPM", "BAC", "WFC", "C", "GS"),

                    "ENERGY",
                    List.of("XOM", "CVX", "OXY", "SLB"),

                    "LARGE_CAP_HEALTHCARE",
                    List.of("PFE", "MRK", "JNJ", "LLY", "UNH")
            );

    public String sectorForTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return "UNKNOWN_SECTOR";
        }

        return tickerToSector.getOrDefault(
                ticker.trim().toUpperCase(),
                "UNKNOWN_SECTOR"
        );
    }

    public List<String> peersForTicker(String ticker) {
        String sector =
                sectorForTicker(ticker);

        return sectorBaskets.getOrDefault(
                sector,
                List.of()
        );
    }
}