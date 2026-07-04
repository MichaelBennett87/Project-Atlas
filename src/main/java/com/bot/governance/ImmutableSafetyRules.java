package com.bot.governance;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Non-negotiable account-survival rules.
 *
 * Autonomous research and strategy mutation can optimize behavior around these
 * limits, but candidate code/config promotion must never weaken or bypass them.
 */
public final class ImmutableSafetyRules {

    private ImmutableSafetyRules() {
    }

    public static ZoneId scheduleZone() {
        return ZoneId.of(env("AUTONOMOUS_SCHEDULE_ZONE", "America/New_York"));
    }

    public static LocalTime nightlyShutdownTime() {
        return parseTime(env("AUTONOMOUS_SHUTDOWN_TIME", "20:00"), LocalTime.of(20, 0));
    }

    public static LocalTime nextDayRestartTime() {
        return parseTime(env("AUTONOMOUS_RESTART_TIME", "04:00"), LocalTime.of(4, 0));
    }

    public static Path liveTradingLockPath() {
        return Path.of(env("LIVE_TRADING_LOCK_PATH", "logs/live_trading.lock"));
    }

    public static Path autonomousReportPath() {
        return Path.of(env("AI_GOVERNANCE_REPORT_PATH", "logs/autonomous_governance_report.txt"));
    }

    public static boolean mayAutonomouslyEdit(Path path) {
        String normalized = path.normalize().toString().replace('\\', '/').toLowerCase();
        if (normalized.contains("/.git/")) return false;
        if (normalized.endsWith(".env")) return false;
        if (normalized.contains("apikey") || normalized.contains("api_key")) return false;
        if (normalized.contains("secret") || normalized.contains("token")) return false;
        if (normalized.contains("credential")) return false;
        if (normalized.contains("governance/immutablesafetyrules.java")) return false;
        return true;
    }

    public static boolean isForbiddenSourceChange(String sourceText) {
        if (sourceText == null) return false;
        String lower = sourceText.toLowerCase();
        return lower.contains("bot_kill_switch=false")
                || lower.contains("kill switch disabled")
                || lower.contains("remove kill switch")
                || lower.contains("bypass risk")
                || lower.contains("riskengine = null")
                || lower.contains("alloworders = true") && lower.contains("dryrun") && lower.contains("false");
    }

    private static LocalTime parseTime(String value, LocalTime fallback) {
        try {
            return value == null || value.isBlank() ? fallback : LocalTime.parse(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
