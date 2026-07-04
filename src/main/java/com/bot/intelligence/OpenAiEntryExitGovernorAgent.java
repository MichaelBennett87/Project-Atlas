package com.bot.intelligence;

import com.bot.master.CatalystQualityGate;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Position;
import com.bot.technical.TechnicalAnalysis;
import com.bot.scalping.VolumeFirstScalpingPolicy;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real OpenAI-backed entry/exit governor.
 *
 * This agent is deliberately a decision layer, not an order router. It returns
 * structured advice that must still pass deterministic risk controls. It can
 * veto, reduce, approve, hold, or request an exit.  When disabled or unavailable,
 * callers fall back to the deterministic dynamic governor.
 */
public final class OpenAiEntryExitGovernorAgent {
    private static final OpenAiEntryExitGovernorAgent INSTANCE = new OpenAiEntryExitGovernorAgent();

    private final boolean enabled = envBool("OPENAI_ENTRY_EXIT_GOVERNOR_ENABLED", true);
    private final boolean failClosed = envBool("OPENAI_ENTRY_EXIT_FAIL_CLOSED", false);
    private final String model = env("OPENAI_ENTRY_EXIT_MODEL", OpenAiClientManager.getInstance().defaultModel());
    private final int maxTokens = envInt("OPENAI_ENTRY_EXIT_MAX_OUTPUT_TOKENS", 900);
    private final double minConfidence = envDouble("OPENAI_ENTRY_EXIT_MIN_CONFIDENCE", 0.55);
    private final Path journalPath = Path.of(env("OPENAI_ENTRY_EXIT_GOVERNOR_JOURNAL", "logs/openai_entry_exit_governor.csv"));
    private final OpenAiClientManager client = OpenAiClientManager.getInstance();

    private OpenAiEntryExitGovernorAgent() {
        ensureJournal();
        if (enabled) {
            System.out.println("OPENAI ENTRY/EXIT GOVERNOR READY: enabled=true model=" + model + " failClosed=" + failClosed + " journal=" + journalPath);
        }
    }

    public static OpenAiEntryExitGovernorAgent getInstance() { return INSTANCE; }
    public boolean isEnabled() { return enabled; }
    public boolean failClosed() { return failClosed; }

    public Decision reviewEntry(StrategyContext context, StrategySignal signal, double accountEquity, double referencePrice, int requestedQty, double deterministicScore) {
        if (!enabled) return Decision.skip("OpenAI entry governor disabled.");
        if (!client.isUsable()) return failClosed ? Decision.veto("OpenAI unavailable and failClosed=true.", 0.0) : Decision.skip("OpenAI unavailable; using deterministic governor.");
        if (context == null || signal == null) return Decision.veto("Missing context or signal.", 0.0);

        String ticker = normalize(signal.getTicker());
        MarketStateDatabase2.State state = MarketStateDatabase2.getInstance().snapshot(ticker);
        double price = referencePrice > 0.0 ? referencePrice : context.getLastPrice();
        String input = "Return strict JSON only. Decide whether this autonomous trading system should enter this trade.\n"
                + "Allowed actions: APPROVE, REDUCE, VETO. Do not bypass risk controls.\n"
                + "ticker=" + ticker + "\n"
                + "direction=" + signal.getDirection() + "\n"
                + "strategy=" + signal.getStrategyName() + "\n"
                + "strategyConfidence=" + fmt(signal.getConfidence()) + "\n"
                + "expectedMovePercent=" + fmt(signal.getExpectedMovePercent()) + "\n"
                + "priorityScore=" + fmt(signal.priorityScore()) + "\n"
                + "requestedQty=" + requestedQty + "\n"
                + "referencePrice=" + fmt(price) + "\n"
                + "accountEquity=" + fmt(accountEquity) + "\n"
                + "deterministicQualityScore=" + fmt(deterministicScore) + "\n"
                + "mission=" + VolumeFirstScalpingPolicy.decisionInstruction() + "\n"
                + "volumeFirstDiagnostics=" + VolumeFirstScalpingPolicy.diagnostics(context) + "\n"
                + "rvol=" + fmt(Math.max(safe(TechnicalAnalysis.relativeVolume(context.getBars(), 20)), VolumeFirstScalpingPolicy.tape(context).relativeVolume)) + "\n"
                + "bullishBreak=" + TechnicalAnalysis.bullishBreak(context.getBars()) + "\n"
                + "reclaimedVwap=" + TechnicalAnalysis.reclaimedVwap(context.getBars(), 30) + "\n"
                + "catalystScore=" + fmt(CatalystQualityGate.tradeableCatalystScore(context.getLatestNews())) + "\n"
                + "marketState=" + stateSummary(state) + "\n"
                + "news=" + abbreviate(context.newsText(), 1200) + "\n"
                + "Output JSON keys: action, confidence, quantity_multiplier, reason, stop_adjustment, target_adjustment.";

        OpenAiClientManager.OpenAiResult result = client.requestJson("ENTRY_EXIT_GOVERNOR_ENTRY", model, instructions(), input, maxTokens);
        if (!result.ok) {
            Decision d = failClosed ? Decision.veto("OpenAI entry review failed: " + result.error, 0.0) : Decision.skip("OpenAI entry review failed; deterministic fallback. " + result.error);
            journal("ENTRY", ticker, d);
            return d;
        }
        Decision d = parseDecision(result.outputText, "APPROVE");
        if (d.confidence < minConfidence && ("APPROVE".equals(d.action) || "REDUCE".equals(d.action))) {
            d = Decision.veto("OpenAI confidence below threshold: " + fmt(d.confidence) + " reason=" + d.reason, d.confidence);
        }
        journal("ENTRY", ticker, d);
        return d;
    }

    public Decision reviewExit(Position position, double currentPrice, double deterministicPnl, double bestGain, String deterministicExitReason) {
        if (!enabled) return Decision.skip("OpenAI exit governor disabled.");
        if (!client.isUsable()) return Decision.skip("OpenAI unavailable; using deterministic exit logic.");
        if (position == null || currentPrice <= 0.0 || position.entryPrice <= 0.0) return Decision.skip("Missing position or price.");

        String ticker = normalize(position.ticker);
        MarketStateDatabase2.State state = MarketStateDatabase2.getInstance().snapshot(ticker);
        long ageSeconds = position.openedAt <= 0 ? -1L : Math.max(0L, (System.currentTimeMillis() - position.openedAt) / 1000L);
        String input = "Return strict JSON only. Decide whether this autonomous trading system should hold or exit this existing position.\n"
                + "Allowed actions: HOLD, EXIT. Hard risk stops remain deterministic and cannot be overridden.\n"
                + "ticker=" + ticker + "\n"
                + "side=" + (position.isShortPosition() ? "SHORT" : "LONG") + "\n"
                + "strategy=" + position.strategyName + "\n"
                + "entryPrice=" + fmt(position.entryPrice) + "\n"
                + "currentPrice=" + fmt(currentPrice) + "\n"
                + "quantity=" + position.quantity + "\n"
                + "ageSeconds=" + ageSeconds + "\n"
                + "pnlPercent=" + fmt(deterministicPnl * 100.0) + "\n"
                + "bestGainPercent=" + fmt(bestGain * 100.0) + "\n"
                + "deterministicExitReason=" + deterministicExitReason + "\n"
                + "marketState=" + stateSummary(state) + "\n"
                + "Output JSON keys: action, confidence, reason, stop_adjustment, target_adjustment.";

        OpenAiClientManager.OpenAiResult result = client.requestJson("ENTRY_EXIT_GOVERNOR_EXIT", model, instructions(), input, maxTokens);
        if (!result.ok) {
            Decision d = Decision.skip("OpenAI exit review failed; deterministic fallback. " + result.error);
            journal("EXIT", ticker, d);
            return d;
        }
        Decision d = parseDecision(result.outputText, "HOLD");
        if (d.confidence < minConfidence && "EXIT".equals(d.action)) {
            d = Decision.hold("OpenAI exit confidence below threshold; holding unless deterministic exit fires. reason=" + d.reason, d.confidence);
        }
        journal("EXIT", ticker, d);
        return d;
    }

    private static String instructions() {
        return "You are the primary entry/exit governor for a self-improving AI scalping and momentum trading system. "
                + VolumeFirstScalpingPolicy.decisionInstruction() + " "
                + "Hard risk controls are enforced outside your response. Your job is timing: approve/reduce/veto based on whether this is a liquid, violent wave worth scalping now. "
                + "Volume, dollar volume, top-volume rank, RVOL, range expansion, velocity, dip recovery, apex failure, spread/slippage risk, and repeatable intraday wave behavior matter more than slow indicators. "
                + "Approve or reduce borderline setups when the tape is liquid and violent enough to scalp. Veto when the tape is dead, illiquid, stale, choppy, or unable to overcome spread/slippage. "
                + "Nightly self-improvement should make tomorrow's scalps better, not convert this into slow swing trading. Return only valid JSON.";
    }

    private static Decision parseDecision(String text, String fallbackAction) {
        String raw = text == null ? "" : text.trim();
        String action = extractString(raw, "action", fallbackAction).toUpperCase(Locale.ROOT).replaceAll("[^A-Z_]", "");
        double confidence = clamp(extractDouble(raw, "confidence", 0.0));
        double multiplier = clampMultiplier(extractDouble(raw, "quantity_multiplier", 1.0));
        double stopAdj = extractDouble(raw, "stop_adjustment", 1.0);
        double targetAdj = extractDouble(raw, "target_adjustment", 1.0);
        String reason = extractString(raw, "reason", abbreviate(raw, 400));
        return new Decision(true, action, confidence, multiplier, stopAdj, targetAdj, reason, raw);
    }

    private static String extractString(String json, String key, String fallback) {
        try {
            Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"(.*?)\\\"", Pattern.DOTALL);
            Matcher m = p.matcher(json);
            if (m.find()) return m.group(1).replace("\\\"", "\"").replace("\\n", " ").trim();
        } catch (Exception ignored) {}
        return fallback == null ? "" : fallback;
    }

    private static double extractDouble(String json, String key, double fallback) {
        try {
            Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
            Matcher m = p.matcher(json);
            if (m.find()) return Double.parseDouble(m.group(1));
        } catch (Exception ignored) {}
        return fallback;
    }

    private static String stateSummary(MarketStateDatabase2.State state) {
        if (state == null) return "none";
        return "direction=" + state.direction
                + ",opportunity=" + fmt(state.opportunityScore)
                + ",long=" + fmt(state.longScore)
                + ",short=" + fmt(state.shortScore)
                + ",risk=" + fmt(state.riskScore)
                + ",liquidity=" + fmt(state.liquidityScore)
                + ",parabolic=" + fmt(state.parabolicScore);
    }

    private void ensureJournal() {
        try {
            Path parent = journalPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (!Files.exists(journalPath) || Files.size(journalPath) == 0) {
                try (BufferedWriter w = Files.newBufferedWriter(journalPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
                    w.write("timestamp,type,ticker,attempted,action,confidence,quantityMultiplier,stopAdjustment,targetAdjustment,reason");
                    w.newLine();
                }
            }
        } catch (Exception e) {
            System.out.println("OPENAI ENTRY/EXIT GOVERNOR JOURNAL INIT FAILED: " + e.getMessage());
        }
    }

    private synchronized void journal(String type, String ticker, Decision d) {
        try {
            Path parent = journalPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(journalPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
                w.write(String.join(",", csv(Instant.now().toString()), csv(type), csv(ticker), Boolean.toString(d.attempted), csv(d.action), fmt(d.confidence), fmt(d.quantityMultiplier), fmt(d.stopAdjustment), fmt(d.targetAdjustment), csv(d.reason)));
                w.newLine();
            }
        } catch (Exception ignored) {}
    }

    public static final class Decision {
        public final boolean attempted;
        public final String action;
        public final double confidence;
        public final double quantityMultiplier;
        public final double stopAdjustment;
        public final double targetAdjustment;
        public final String reason;
        public final String raw;
        private Decision(boolean attempted, String action, double confidence, double quantityMultiplier, double stopAdjustment, double targetAdjustment, String reason, String raw) {
            this.attempted = attempted;
            this.action = action == null ? "SKIP" : action.toUpperCase(Locale.ROOT);
            this.confidence = clamp(confidence);
            this.quantityMultiplier = clampMultiplier(quantityMultiplier);
            this.stopAdjustment = Double.isFinite(stopAdjustment) ? stopAdjustment : 1.0;
            this.targetAdjustment = Double.isFinite(targetAdjustment) ? targetAdjustment : 1.0;
            this.reason = reason == null ? "" : reason;
            this.raw = raw == null ? "" : raw;
        }
        public static Decision skip(String reason) { return new Decision(false, "SKIP", 0.0, 1.0, 1.0, 1.0, reason, ""); }
        public static Decision veto(String reason, double confidence) { return new Decision(true, "VETO", confidence, 0.0, 1.0, 1.0, reason, ""); }
        public static Decision hold(String reason, double confidence) { return new Decision(true, "HOLD", confidence, 1.0, 1.0, 1.0, reason, ""); }
        public boolean vetoesEntry() { return "VETO".equals(action) || "BLOCK".equals(action) || "REJECT".equals(action); }
        public boolean reducesEntry() { return "REDUCE".equals(action); }
        public boolean exitsPosition() { return "EXIT".equals(action) || "SELL".equals(action) || "COVER".equals(action); }
    }

    private static double clamp(double v) { return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0, v)) : 0.0; }
    private static double clampMultiplier(double v) { return Double.isFinite(v) ? Math.max(0.0, Math.min(1.5, v)) : 1.0; }
    private static double safe(double v) { return Double.isFinite(v) ? v : 0.0; }
    private static String normalize(String ticker) { return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", ""); }
    private static String fmt(double v) { return String.format(Locale.US, "%.4f", Double.isFinite(v) ? v : 0.0); }
    private static String csv(String value) { String v = value == null ? "" : value; return '"' + v.replace("\"", "\"\"").replace('\n',' ').replace('\r',' ') + '"'; }
    private static String abbreviate(String value, int max) { if (value == null) return ""; return value.length() <= max ? value : value.substring(0, max) + "..."; }
    private static String env(String key, String fallback) { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : v.trim(); }
    private static boolean envBool(String key, boolean fallback) { String v = System.getenv(key); if (v == null || v.isBlank()) return fallback; String x = v.trim().toLowerCase(Locale.ROOT); return x.equals("true") || x.equals("1") || x.equals("yes") || x.equals("on"); }
    private static int envInt(String key, int fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); } catch(Exception e) { return fallback; } }
    private static double envDouble(String key, double fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim()); } catch(Exception e) { return fallback; } }
}
