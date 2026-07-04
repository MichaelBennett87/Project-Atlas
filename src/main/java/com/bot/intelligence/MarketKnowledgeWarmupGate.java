package com.bot.intelligence;

/**
 * Shared warm-up guard for state-driven agents.
 *
 * The market-state agents should not repeatedly scan and publish empty state while
 * the REST research scheduler is still warming the MarketKnowledgeDatabase. This guard
 * keeps logs honest and avoids zero-symbol opportunity scans during startup. It does
 * not block provider ingestion; it only tells downstream agents when enough local
 * knowledge exists to make their scan meaningful.
 */
public final class MarketKnowledgeWarmupGate {
    private MarketKnowledgeWarmupGate() {}

    public static boolean isWarm() {
        if (!boolEnv("MARKET_KNOWLEDGE_WARMUP_GATE_ENABLED", true)) return true;
        int active = MarketKnowledgeDatabase.getInstance().topByActivity(maxProbe()).size();
        return active >= minSymbols();
    }

    public static String status() {
        int active = MarketKnowledgeDatabase.getInstance().topByActivity(maxProbe()).size();
        return "activeKnowledge=" + active + " minRequired=" + minSymbols();
    }

    public static int activeSymbols() {
        return MarketKnowledgeDatabase.getInstance().topByActivity(maxProbe()).size();
    }

    private static int minSymbols() { return Math.max(1, intEnv("MARKET_KNOWLEDGE_WARMUP_MIN_SYMBOLS", 10)); }
    private static int maxProbe() { return Math.max(25, intEnv("MARKET_KNOWLEDGE_WARMUP_PROBE_TOP", 1000)); }
    private static int intEnv(String k, int f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Integer.parseInt(v.trim()); } catch (Exception e) { return f; } }
    private static boolean boolEnv(String k, boolean f) { String v = System.getenv(k); if (v == null || v.isBlank()) return f; String x = v.trim().toLowerCase(java.util.Locale.ROOT); return x.equals("true") || x.equals("1") || x.equals("yes") || x.equals("on"); }
}
