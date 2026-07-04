package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Learns lightweight per-strategy performance weights from the trade outcome
 * journal. This is intentionally bounded and deterministic: it does not rewrite
 * source while the bot is live, but it lets strategies that are actually making
 * money gain influence and strategies that are bleeding lose influence.
 */
public class AdaptiveStrategyPerformanceTracker {

    private final Path outcomePath;
    private final long refreshMs;
    private final Map<String, Stats> statsByStrategy = new ConcurrentHashMap<>();
    private volatile long lastRefreshAt = 0L;

    public AdaptiveStrategyPerformanceTracker() {
        this(Path.of(System.getenv().getOrDefault("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv")));
    }

    public AdaptiveStrategyPerformanceTracker(Path outcomePath) {
        this.outcomePath = outcomePath;
        this.refreshMs = envLong("ADAPTIVE_STRATEGY_WEIGHT_REFRESH_MS", 60_000L);
    }

    public double strategyWeight(String strategyName) {
        refreshIfNeeded();
        String key = normalize(strategyName);
        Stats stats = statsByStrategy.get(key);
        if (stats == null || stats.closedTrades < envInt("ADAPTIVE_STRATEGY_WEIGHT_MIN_TRADES", 3)) {
            return 1.0;
        }
        return stats.weight();
    }

    public String describe(String strategyName) {
        refreshIfNeeded();
        Stats stats = statsByStrategy.get(normalize(strategyName));
        if (stats == null) {
            return "strategyWeight=1.000 sample=0";
        }
        return String.format("strategyWeight=%.3f sizingMultiplier=%.3f trades=%d winRate=%.2f profitFactor=%.2f avgPnl=%.4f avgDrawdown=%.4f worstDrawdown=%.4f maxLossStreak=%d",
                stats.weight(),
                stats.sizingMultiplier(),
                stats.closedTrades,
                stats.winRate(),
                stats.profitFactor(),
                stats.avgPnlPercent(),
                stats.avgDrawdownPercent(),
                stats.worstDrawdown,
                stats.maxLossStreak);
    }

    private void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshAt < refreshMs) {
            return;
        }
        lastRefreshAt = now;
        load();
    }

    private void load() {
        if (outcomePath == null || !Files.exists(outcomePath)) {
            return;
        }
        Map<String, Stats> next = new ConcurrentHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(outcomePath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return;
            }
            String[] header = splitCsv(headerLine);
            int eventIdx = indexOf(header, "eventType");
            int strategyIdx = indexOf(header, "strategyName");
            int pnlIdx = indexOf(header, "currentPnlPercent");
            int realizedIdx = indexOf(header, "realizedPnlDollars");
            int drawdownIdx = indexOf(header, "maxDrawdownPercent");
            int syncedIdx = indexOf(header, "syncedFromBroker");
            if (eventIdx < 0 || strategyIdx < 0) {
                return;
            }
            String line;
            int rows = 0;
            int maxRows = envInt("ADAPTIVE_STRATEGY_WEIGHT_MAX_ROWS", 20_000);
            while ((line = reader.readLine()) != null) {
                if (++rows > maxRows) {
                    break;
                }
                String[] cols = splitCsv(line);
                if (cols.length <= Math.max(eventIdx, strategyIdx)) {
                    continue;
                }
                String event = clean(cols[eventIdx]).toUpperCase();
                String strategy = normalize(clean(cols[strategyIdx]));
                String synced = syncedIdx >= 0 && cols.length > syncedIdx ? clean(cols[syncedIdx]) : "";
                if (!TradeOutcomeTrainingFilter.isTrainingEligible(event, strategy, synced)) {
                    continue;
                }
                double pnlPercent = cols.length > pnlIdx && pnlIdx >= 0 ? parseDouble(cols[pnlIdx], 0.0) : 0.0;
                double realized = cols.length > realizedIdx && realizedIdx >= 0 ? parseDouble(cols[realizedIdx], 0.0) : 0.0;
                double drawdown = cols.length > drawdownIdx && drawdownIdx >= 0 ? parseDouble(cols[drawdownIdx], 0.0) : 0.0;
                next.computeIfAbsent(strategy, k -> new Stats()).add(pnlPercent, realized, drawdown);
            }
            statsByStrategy.clear();
            statsByStrategy.putAll(next);
        } catch (IOException e) {
            System.out.println("ADAPTIVE STRATEGY WEIGHT LOAD FAILED: " + e.getMessage());
        }
    }

    public double sizingMultiplier(String strategyName) {
        refreshIfNeeded();
        Stats stats = statsByStrategy.get(normalize(strategyName));
        if (stats == null || stats.closedTrades < envInt("ADAPTIVE_STRATEGY_SIZING_MIN_TRADES", 5)) {
            return 1.0;
        }
        return stats.sizingMultiplier();
    }

    private static final class Stats {
        int closedTrades;
        int wins;
        double pnlPercentSum;
        double grossProfit;
        double grossLoss;
        double drawdownSum;
        double worstDrawdown;
        int currentLossStreak;
        int maxLossStreak;

        void add(double pnlPercent, double realizedDollars, double maxDrawdownPercent) {
            closedTrades++;
            pnlPercentSum += pnlPercent;
            double drawdown = Math.abs(maxDrawdownPercent);
            drawdownSum += drawdown;
            worstDrawdown = Math.max(worstDrawdown, drawdown);
            double pnl = Math.abs(realizedDollars) > 0.000001 ? realizedDollars : pnlPercent;
            if (pnl > 0) {
                wins++;
                grossProfit += pnl;
                currentLossStreak = 0;
            } else if (pnl < 0) {
                grossLoss += Math.abs(pnl);
                currentLossStreak++;
                maxLossStreak = Math.max(maxLossStreak, currentLossStreak);
            }
        }

        double winRate() { return closedTrades <= 0 ? 0.0 : (double) wins / closedTrades; }
        double avgPnlPercent() { return closedTrades <= 0 ? 0.0 : pnlPercentSum / closedTrades; }
        double profitFactor() { return grossLoss <= 0.0 ? (grossProfit > 0.0 ? 4.0 : 1.0) : grossProfit / grossLoss; }
        double avgDrawdownPercent() { return closedTrades <= 0 ? 0.0 : drawdownSum / closedTrades; }

        double weight() {
            double pf = Math.max(0.25, Math.min(4.0, profitFactor()));
            double wr = winRate();
            double avg = avgPnlPercent();
            double raw = 1.0 + (pf - 1.0) * 0.18 + (wr - 0.50) * 0.35 + avg * 2.5;
            return Math.max(0.55, Math.min(1.65, raw));
        }

        double sizingMultiplier() {
            double multiplier = 1.0;
            double pf = profitFactor();
            double wr = winRate();
            double avg = avgPnlPercent();
            double avgDrawdown = avgDrawdownPercent();

            if (pf < envDouble("ADAPTIVE_STRATEGY_SIZING_WEAK_PROFIT_FACTOR", 0.95) || avg < 0.0) {
                multiplier *= envDouble("ADAPTIVE_STRATEGY_SIZING_WEAK_MULTIPLIER", 0.70);
            }
            if (pf < envDouble("ADAPTIVE_STRATEGY_SIZING_SEVERE_PROFIT_FACTOR", 0.75) && avg < 0.0) {
                multiplier *= envDouble("ADAPTIVE_STRATEGY_SIZING_SEVERE_MULTIPLIER", 0.65);
            }
            if (avgDrawdown >= envDouble("ADAPTIVE_STRATEGY_SIZING_AVG_DRAWDOWN_THROTTLE", 0.035)) {
                multiplier *= envDouble("ADAPTIVE_STRATEGY_SIZING_DRAWDOWN_MULTIPLIER", 0.75);
            }
            if (worstDrawdown >= envDouble("ADAPTIVE_STRATEGY_SIZING_WORST_DRAWDOWN_THROTTLE", 0.09)) {
                multiplier *= envDouble("ADAPTIVE_STRATEGY_SIZING_WORST_DRAWDOWN_MULTIPLIER", 0.80);
            }
            if (maxLossStreak >= envInt("ADAPTIVE_STRATEGY_SIZING_LOSS_STREAK_THROTTLE", 3)) {
                multiplier *= envDouble("ADAPTIVE_STRATEGY_SIZING_LOSS_STREAK_MULTIPLIER", 0.80);
            }

            int expansionMinTrades = envInt("ADAPTIVE_STRATEGY_SIZING_EXPAND_MIN_TRADES", 12);
            if (closedTrades >= expansionMinTrades
                    && pf >= envDouble("ADAPTIVE_STRATEGY_SIZING_STRONG_PROFIT_FACTOR", 1.35)
                    && wr >= envDouble("ADAPTIVE_STRATEGY_SIZING_STRONG_WIN_RATE", 0.55)
                    && avg > 0.0
                    && avgDrawdown < envDouble("ADAPTIVE_STRATEGY_SIZING_STRONG_MAX_AVG_DRAWDOWN", 0.03)
                    && maxLossStreak < envInt("ADAPTIVE_STRATEGY_SIZING_STRONG_MAX_LOSS_STREAK", 3)) {
                multiplier *= envDouble("ADAPTIVE_STRATEGY_SIZING_STRONG_MULTIPLIER", 1.12);
            }

            return Math.max(
                    envDouble("ADAPTIVE_STRATEGY_SIZING_MIN_MULTIPLIER", 0.35),
                    Math.min(envDouble("ADAPTIVE_STRATEGY_SIZING_MAX_MULTIPLIER", 1.25), multiplier)
            );
        }
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    private static int indexOf(String[] header, String name) {
        if (header == null) return -1;
        for (int i=0;i<header.length;i++) if (name.equalsIgnoreCase(clean(header[i]))) return i;
        return -1;
    }
    private static String clean(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length()-1).replace("\"\"", "\"");
        return v;
    }
    private static String[] splitCsv(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i=0;i<line.length();i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i+1) == '"') { sb.append('"'); i++; }
                else inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) { out.add(sb.toString()); sb.setLength(0); }
            else sb.append(c);
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }
    private static double parseDouble(String value, double fallback) { try { return Double.parseDouble(clean(value)); } catch(Exception e) { return fallback; } }
    private static long envLong(String key,long fallback){try{String v=System.getenv(key);return v==null||v.isBlank()?fallback:Long.parseLong(v.trim());}catch(Exception e){return fallback;}}
    private static int envInt(String key,int fallback){try{String v=System.getenv(key);return v==null||v.isBlank()?fallback:Integer.parseInt(v.trim());}catch(Exception e){return fallback;}}
    private static double envDouble(String key,double fallback){try{String v=System.getenv(key);return v==null||v.isBlank()?fallback:Double.parseDouble(v.trim());}catch(Exception e){return fallback;}}
}
