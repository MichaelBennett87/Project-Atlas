package com.bot;

import com.bot.governance.ImmutableSafetyRules;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Dry-run validation for the always-on autonomous trading day.
 *
 * This does not start the live trading engine, call a broker, or run research.
 * It verifies that the 04:00-20:00 live window, live lock handoff, offline
 * improvement environment, and expected report paths are ready before the bot
 * is trusted to run unattended.
 */
public final class AutonomousOpsValidationMain {
    private static final String PASS = "PASS";
    private static final String FAIL = "FAIL";

    private AutonomousOpsValidationMain() {
    }

    public static void main(String[] args) throws Exception {
        ValidationResult result = runValidation();
        System.out.println("AUTONOMOUS OPS VALIDATION COMPLETE: status=" + result.status
                + " report=" + result.reportPath.toAbsolutePath()
                + " health=" + result.healthPath.toAbsolutePath()
                + " journal=" + result.journalPath.toAbsolutePath());

        if (!PASS.equals(result.status) && envBoolean("OPS_VALIDATION_EXIT_NONZERO_ON_FAIL", true)) {
            System.exit(1);
        }
    }

    static ValidationResult runValidation() throws Exception {
        ZoneId zone = ImmutableSafetyRules.scheduleZone();
        LocalTime restartTime = ImmutableSafetyRules.nextDayRestartTime();
        LocalTime shutdownTime = ImmutableSafetyRules.nightlyShutdownTime();
        LocalTime expectedStart = parseLocalTime(env("OPS_VALIDATION_EXPECTED_START", "04:00"), LocalTime.of(4, 0));
        LocalTime expectedEnd = parseLocalTime(env("OPS_VALIDATION_EXPECTED_END", "20:00"), LocalTime.of(20, 0));

        Path reportPath = Path.of(env("AUTONOMOUS_OPS_VALIDATION_REPORT", "logs/autonomous_ops_validation_report.txt"));
        Path healthPath = Path.of(env("AUTONOMOUS_OPS_VALIDATION_HEALTH", "logs/autonomous_ops_validation_health.properties"));
        Path journalPath = Path.of(env("AUTONOMOUS_OPS_VALIDATION_JOURNAL", "logs/autonomous_ops_validation_journal.csv"));
        Path validationDir = Path.of(env("AUTONOMOUS_OPS_VALIDATION_DIR", "logs/autonomous_ops_validation"));
        Files.createDirectories(validationDir);

        AutonomousSupervisorJournal journal = new AutonomousSupervisorJournal(journalPath);
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> phaseChecks = new ArrayList<>();
        List<String> environmentChecks = new ArrayList<>();
        List<String> artifactChecks = new ArrayList<>();
        List<String> timeline = new ArrayList<>();
        Map<String, String> checkStatus = new LinkedHashMap<>();

        boolean schedulePassed = validateSchedule(restartTime, shutdownTime, expectedStart, expectedEnd, failures, warnings);
        checkStatus.put("schedule", status(schedulePassed));
        journal.event("OPS_VALIDATION", status(schedulePassed), Map.of(
                "check", "schedule",
                "restartTime", restartTime.toString(),
                "shutdownTime", shutdownTime.toString()));

        boolean phasePassed = validatePhaseMatrix(zone, restartTime, shutdownTime, phaseChecks, failures, journal);
        checkStatus.put("phaseMatrix", status(phasePassed));

        ZonedDateTime scheduledShutdown = ZonedDateTime.now(zone).with(shutdownTime);
        if (!scheduledShutdown.isAfter(ZonedDateTime.now(zone))) {
            scheduledShutdown = scheduledShutdown.plusDays(1);
        }
        Map<String, String> liveEnv = ScheduledAutonomousTradingSupervisorMain.validationLiveEnvironment(scheduledShutdown, restartTime, shutdownTime);
        boolean liveEnvPassed = validateLiveEnvironment(liveEnv, restartTime, shutdownTime, scheduledShutdown, environmentChecks, failures);
        checkStatus.put("liveEnvironment", status(liveEnvPassed));
        journal.event("OPS_VALIDATION", status(liveEnvPassed), Map.of("check", "liveEnvironment"));

        Map<String, String> evolutionEnv = ScheduledAutonomousTradingSupervisorMain.validationEvolutionEnvironment(restartTime, shutdownTime);
        boolean evolutionEnvPassed = validateEvolutionEnvironment(evolutionEnv, restartTime, shutdownTime, environmentChecks, failures);
        checkStatus.put("offlineImprovementEnvironment", status(evolutionEnvPassed));
        journal.event("OPS_VALIDATION", status(evolutionEnvPassed), Map.of("check", "offlineImprovementEnvironment"));

        boolean lockPassed = validateLockHandoff(validationDir, timeline, failures, journal);
        checkStatus.put("lockHandoff", status(lockPassed));

        boolean artifactPathPassed = validateArtifactPaths(artifactChecks, warnings, failures);
        checkStatus.put("artifactPaths", status(artifactPathPassed));

        timeline.add("WAIT_FOR_0400 simulated target=" + restartTime + " zone=" + zone);
        timeline.add("LIVE_MAIN verified class=" + ScheduledAutonomousTradingSupervisorMain.liveMainClassName() + " childStarted=false");
        timeline.add("TRADING_OFF_2000 simulated target=" + shutdownTime + " zone=" + zone);
        timeline.add("OFFLINE_IMPROVEMENT_MAIN verified class=" + ScheduledAutonomousTradingSupervisorMain.evolutionMainClassName() + " childStarted=false");
        timeline.add("NEXT_RESTART_SCHEDULED simulated nextWindowStart=" + nextWindowStart(zone, restartTime));

        String overallStatus = failures.isEmpty() ? PASS : FAIL;
        writeReport(reportPath, overallStatus, zone, restartTime, shutdownTime, expectedStart, expectedEnd,
                checkStatus, phaseChecks, environmentChecks, artifactChecks, timeline, warnings, failures);
        writeHealth(healthPath, overallStatus, zone, restartTime, shutdownTime, checkStatus, reportPath, journalPath, warnings, failures);

        journal.event("OPS_VALIDATION", overallStatus, Map.of(
                "reportPath", reportPath.toString(),
                "healthPath", healthPath.toString(),
                "failureCount", String.valueOf(failures.size()),
                "warningCount", String.valueOf(warnings.size())));
        return new ValidationResult(overallStatus, reportPath, healthPath, journalPath);
    }

    private static boolean validateSchedule(LocalTime restartTime,
                                            LocalTime shutdownTime,
                                            LocalTime expectedStart,
                                            LocalTime expectedEnd,
                                            List<String> failures,
                                            List<String> warnings) {
        boolean matches = restartTime.equals(expectedStart) && shutdownTime.equals(expectedEnd);
        if (matches) {
            return true;
        }
        String message = "Expected live window " + expectedStart + "-" + expectedEnd
                + " but configured window is " + restartTime + "-" + shutdownTime;
        if (envBoolean("OPS_VALIDATION_ALLOW_SCHEDULE_OVERRIDE", false)) {
            warnings.add(message + " because OPS_VALIDATION_ALLOW_SCHEDULE_OVERRIDE=true");
            return true;
        }
        failures.add(message);
        return false;
    }

    private static boolean validatePhaseMatrix(ZoneId zone,
                                               LocalTime restartTime,
                                               LocalTime shutdownTime,
                                               List<String> phaseChecks,
                                               List<String> failures,
                                               AutonomousSupervisorJournal journal) {
        if (!restartTime.isBefore(shutdownTime)) {
            String message = "Autonomous ops validation expects a same-day live window, got "
                    + restartTime + "-" + shutdownTime;
            failures.add(message);
            phaseChecks.add("unsupportedWindow expected=sameDay actual=" + restartTime + "-" + shutdownTime + " status=FAIL");
            return false;
        }

        LocalDate date = LocalDate.now(zone);
        long liveMinutes = Math.max(1L, Duration.between(restartTime, shutdownTime).toMinutes());
        LocalTime midpoint = restartTime.plusMinutes(Math.max(1L, liveMinutes / 2L));

        boolean passed = true;
        passed &= expectPhase(date, zone, restartTime.minusMinutes(1), restartTime, shutdownTime,
                "oneMinuteBeforeStart", "BEFORE_LIVE_START", phaseChecks, failures, journal);
        passed &= expectPhase(date, zone, restartTime, restartTime, shutdownTime,
                "atStart", "LIVE_SESSION", phaseChecks, failures, journal);
        passed &= expectPhase(date, zone, midpoint, restartTime, shutdownTime,
                "midSession", "LIVE_SESSION", phaseChecks, failures, journal);
        passed &= expectPhase(date, zone, shutdownTime.minusMinutes(1), restartTime, shutdownTime,
                "oneMinuteBeforeShutdown", "LIVE_SESSION", phaseChecks, failures, journal);
        passed &= expectPhase(date, zone, shutdownTime, restartTime, shutdownTime,
                "atShutdown", "AFTER_LIVE_SHUTDOWN", phaseChecks, failures, journal);
        passed &= expectPhase(date, zone, shutdownTime.plusMinutes(1), restartTime, shutdownTime,
                "oneMinuteAfterShutdown", "AFTER_LIVE_SHUTDOWN", phaseChecks, failures, journal);
        passed &= expectPhase(date.plusDays(1), zone, restartTime, restartTime, shutdownTime,
                "nextDayStart", "LIVE_SESSION", phaseChecks, failures, journal);
        return passed;
    }

    private static boolean expectPhase(LocalDate date,
                                       ZoneId zone,
                                       LocalTime checkTime,
                                       LocalTime restartTime,
                                       LocalTime shutdownTime,
                                       String label,
                                       String expected,
                                       List<String> phaseChecks,
                                       List<String> failures,
                                       AutonomousSupervisorJournal journal) {
        ZonedDateTime checkAt = ZonedDateTime.of(date, checkTime, zone);
        String actual = ScheduledAutonomousTradingSupervisorMain.phaseNameFor(checkAt, restartTime, shutdownTime);
        boolean passed = expected.equals(actual);
        String line = label + " at=" + checkAt + " expected=" + expected + " actual=" + actual + " status=" + status(passed);
        phaseChecks.add(line);
        if (!passed) {
            failures.add("Phase check failed: " + line);
        }
        journal.event("OPS_VALIDATION_PHASE", status(passed), Map.of(
                "label", label,
                "at", checkAt.toString(),
                "expected", expected,
                "actual", actual));
        return passed;
    }

    private static boolean validateLiveEnvironment(Map<String, String> liveEnv,
                                                   LocalTime restartTime,
                                                   LocalTime shutdownTime,
                                                   ZonedDateTime scheduledShutdown,
                                                   List<String> environmentChecks,
                                                   List<String> failures) {
        boolean passed = true;
        passed &= requireEnv(liveEnv, "AUTO_SHUTDOWN_AT_8PM", "false", "live", environmentChecks, failures);
        passed &= requireEnv(liveEnv, "SUPERVISOR_MANAGED_SESSION", "true", "live", environmentChecks, failures);
        passed &= requireEnv(liveEnv, "SUPERVISOR_SCHEDULED_SHUTDOWN", scheduledShutdown.toString(), "live", environmentChecks, failures);
        passed &= requireEnv(liveEnv, "AUTONOMOUS_RESTART_TIME", restartTime.toString(), "live", environmentChecks, failures);
        passed &= requireEnv(liveEnv, "AUTONOMOUS_SHUTDOWN_TIME", shutdownTime.toString(), "live", environmentChecks, failures);
        passed &= requireEnv(liveEnv, "ENTRY_EXIT_POLICY_AUTO_LOAD", "true", "live", environmentChecks, failures);
        passed &= requireNonBlank(liveEnv, "LIVE_TRADING_LOCK_PATH", "live", environmentChecks, failures);
        passed &= requireNonBlank(liveEnv, "ALPACA_NEWS_WS_LOCK_PATH", "live", environmentChecks, failures);
        return passed;
    }

    private static boolean validateEvolutionEnvironment(Map<String, String> evolutionEnv,
                                                        LocalTime restartTime,
                                                        LocalTime shutdownTime,
                                                        List<String> environmentChecks,
                                                        List<String> failures) {
        boolean passed = true;
        passed &= requireEnv(evolutionEnv, "TRADING_ENABLED", "false", "offlineImprovement", environmentChecks, failures);
        passed &= requireEnv(evolutionEnv, "DRY_RUN", "true", "offlineImprovement", environmentChecks, failures);
        passed &= requireEnv(evolutionEnv, "OFFLINE_AUTONOMOUS_EVOLUTION", "true", "offlineImprovement", environmentChecks, failures);
        passed &= requireEnv(evolutionEnv, "AUTONOMOUS_RESTART_TIME", restartTime.toString(), "offlineImprovement", environmentChecks, failures);
        passed &= requireEnv(evolutionEnv, "AUTONOMOUS_SHUTDOWN_TIME", shutdownTime.toString(), "offlineImprovement", environmentChecks, failures);
        passed &= requireEnv(evolutionEnv, "AUTONOMOUS_SOURCE_REWRITE_LIVE", "false", "offlineImprovement", environmentChecks, failures);
        passed &= requireEnv(evolutionEnv, "REQUIRE_POLICY_VALIDATION_BEFORE_PROMOTION", "true", "offlineImprovement", environmentChecks, failures);
        passed &= requireNonBlank(evolutionEnv, "LIVE_TRADING_LOCK_PATH", "offlineImprovement", environmentChecks, failures);
        return passed;
    }

    private static boolean validateLockHandoff(Path validationDir,
                                               List<String> timeline,
                                               List<String> failures,
                                               AutonomousSupervisorJournal journal) {
        Path simulatedLock = validationDir.resolve("simulated_live_trading.lock");
        long compressedLiveMs = envLong("OPS_VALIDATION_COMPRESSED_LIVE_MS", 2_000L);
        long compressedOfflineMs = envLong("OPS_VALIDATION_COMPRESSED_OFFLINE_MS", 1_000L);
        try {
            Files.createDirectories(validationDir);
            Files.deleteIfExists(simulatedLock);
            Files.writeString(simulatedLock,
                    "validation=true" + System.lineSeparator()
                            + "purpose=simulate supervisor live lock handoff" + System.lineSeparator()
                            + "createdAt=" + ZonedDateTime.now() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            timeline.add("LIVE_LOCK_PRESENT path=" + simulatedLock + " compressedLiveMs=" + compressedLiveMs);
            journal.event("OPS_VALIDATION_LOCK", "PRESENT", Map.of("path", simulatedLock.toString()));

            if (envBoolean("OPS_VALIDATION_SLEEP", false)) {
                Thread.sleep(compressedLiveMs);
            }
            if (!Files.exists(simulatedLock)) {
                failures.add("Simulated live lock was not present during compressed live session: " + simulatedLock);
                return false;
            }

            Files.deleteIfExists(simulatedLock);
            timeline.add("LIVE_LOCK_RELEASED path=" + simulatedLock + " compressedOfflineMs=" + compressedOfflineMs);
            journal.event("OPS_VALIDATION_LOCK", "RELEASED", Map.of("path", simulatedLock.toString()));
            if (envBoolean("OPS_VALIDATION_SLEEP", false)) {
                Thread.sleep(compressedOfflineMs);
            }
            if (Files.exists(simulatedLock)) {
                failures.add("Simulated live lock still exists before offline improvement: " + simulatedLock);
                return false;
            }

            timeline.add("OFFLINE_IMPROVEMENT_START allowed=true liveLockPresent=false");
            return true;
        } catch (Exception e) {
            failures.add("Lock handoff validation failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean validateArtifactPaths(List<String> artifactChecks,
                                                 List<String> warnings,
                                                 List<String> failures) {
        Map<String, Path> paths = new LinkedHashMap<>();
        addArtifactPath(paths, "selfImprovementReport", "AI_SELF_IMPROVEMENT_MASTER_REPORT_PATH", "logs/autonomous_self_improvement_report.txt");
        addArtifactPath(paths, "simulationStrategyPolicy", "SIMULATION_STRATEGY_POLICY_PATH", "logs/simulation_strategy_policy.properties");
        addArtifactPath(paths, "barByBarPolicy", "BAR_BY_BAR_SIMULATION_POLICY_PATH", "logs/bar_by_bar_simulation_policy.properties");
        addArtifactPath(paths, "barByBarReport", "BAR_BY_BAR_SIMULATION_REPORT_PATH", "logs/bar_by_bar_simulation_report.txt");
        addArtifactPath(paths, "barByBarTrades", "BAR_BY_BAR_SIMULATION_TRADES_PATH", "logs/bar_by_bar_simulation_trades.csv");
        addArtifactPath(paths, "barByBarCandidateWatchlist", "BAR_BY_BAR_CANDIDATE_WATCHLIST_PATH", "logs/bar_by_bar_candidate_watchlist.csv");
        addArtifactPath(paths, "candidateRetestQueue", "CANDIDATE_RETEST_QUEUE_PATH", "logs/candidate_retest_queue.csv");
        addArtifactPath(paths, "candidateRetestQueueReport", "CANDIDATE_RETEST_QUEUE_REPORT_PATH", "logs/candidate_retest_queue_report.txt");
        addArtifactPath(paths, "candidateRetestQueuePolicy", "CANDIDATE_RETEST_QUEUE_POLICY_PATH", "logs/candidate_retest_queue_policy.properties");
        addArtifactPath(paths, "candidateRetestQueueHealth", "CANDIDATE_RETEST_QUEUE_HEALTH_PATH", "logs/candidate_retest_queue_health.properties");
        addArtifactPath(paths, "candidateRetestState", "CANDIDATE_RETEST_QUEUE_STATE_PATH", "logs/candidate_retest_queue_state.properties");
        addArtifactPath(paths, "barByBarCandidateRetestPolicy", "BAR_BY_BAR_CANDIDATE_RETEST_POLICY_PATH", "logs/bar_by_bar_candidate_retest_policy.properties");
        addArtifactPath(paths, "barByBarCandidateRetestReport", "BAR_BY_BAR_CANDIDATE_RETEST_REPORT_PATH", "logs/bar_by_bar_candidate_retest_report.txt");
        addArtifactPath(paths, "barByBarCandidateRetestTrades", "BAR_BY_BAR_CANDIDATE_RETEST_TRADES_PATH", "logs/bar_by_bar_candidate_retest_trades.csv");
        addArtifactPath(paths, "barByBarCandidateRetestWatchlist", "BAR_BY_BAR_CANDIDATE_RETEST_WATCHLIST_PATH", "logs/bar_by_bar_candidate_retest_watchlist.csv");
        addArtifactPath(paths, "watchlistShadowSamplerHealth", "WATCHLIST_SHADOW_SAMPLER_HEALTH_PATH", "logs/watchlist_shadow_sampler_health.properties");
        addArtifactPath(paths, "watchlistShadowSamplerEvents", "WATCHLIST_SHADOW_SAMPLER_EVENTS_PATH", "logs/watchlist_shadow_sampler_events.csv");
        addArtifactPath(paths, "executionCostPolicy", "EXECUTION_COST_POLICY_PATH", "logs/execution_cost_policy.properties");
        addArtifactPath(paths, "executionCostReport", "EXECUTION_COST_REPORT_PATH", "logs/execution_cost_report.txt");
        addArtifactPath(paths, "executionCostMatrix", "EXECUTION_COST_MATRIX_PATH", "logs/execution_cost_matrix.csv");
        addArtifactPath(paths, "executionCostHealth", "EXECUTION_COST_HEALTH_PATH", "logs/execution_cost_health.properties");
        addArtifactPath(paths, "preTradeCalibrationPolicy", "PRE_TRADE_CALIBRATION_POLICY_PATH", "logs/pre_trade_calibration_policy.properties");
        addArtifactPath(paths, "preTradeCalibrationReport", "PRE_TRADE_CALIBRATION_REPORT_PATH", "logs/pre_trade_calibration_report.txt");
        addArtifactPath(paths, "preTradeCalibrationMatrix", "PRE_TRADE_CALIBRATION_MATRIX_PATH", "logs/pre_trade_calibration_matrix.csv");
        addArtifactPath(paths, "preTradeCalibrationHealth", "PRE_TRADE_CALIBRATION_HEALTH_PATH", "logs/pre_trade_calibration_health.properties");
        addArtifactPath(paths, "preTradeCalibrationAudit", "PRE_TRADE_CALIBRATION_AUDIT_PATH", "logs/pre_trade_calibration_audit.csv");
        addArtifactPath(paths, "preTradeCalibrationAuditReport", "PRE_TRADE_CALIBRATION_AUDIT_REPORT_PATH", "logs/pre_trade_calibration_audit_report.txt");
        addArtifactPath(paths, "preTradeCalibrationAuditSummary", "PRE_TRADE_CALIBRATION_AUDIT_SUMMARY_PATH", "logs/pre_trade_calibration_audit_summary.csv");
        addArtifactPath(paths, "preTradeCalibrationAuditHealth", "PRE_TRADE_CALIBRATION_AUDIT_HEALTH_PATH", "logs/pre_trade_calibration_audit_health.properties");
        addArtifactPath(paths, "exitShadowTournamentPolicy", "EXIT_SHADOW_TOURNAMENT_POLICY_PATH", "logs/exit_shadow_tournament_policy.properties");
        addArtifactPath(paths, "exitShadowTournamentReport", "EXIT_SHADOW_TOURNAMENT_REPORT_PATH", "logs/exit_shadow_tournament_report.txt");
        addArtifactPath(paths, "exitShadowTournamentMatrix", "EXIT_SHADOW_TOURNAMENT_MATRIX_PATH", "logs/exit_shadow_tournament_matrix.csv");
        addArtifactPath(paths, "exitShadowTournamentHealth", "EXIT_SHADOW_TOURNAMENT_HEALTH_PATH", "logs/exit_shadow_tournament_health.properties");
        addArtifactPath(paths, "paperTradingStrategyPolicy", "PAPER_TRADING_STRATEGY_POLICY_PATH", "logs/paper_trading_strategy_policy.properties");
        addArtifactPath(paths, "paperTradingPerformanceGateReport", "PAPER_TRADING_PERFORMANCE_GATE_REPORT", "logs/paper_trading_performance_gate_report.txt");
        addArtifactPath(paths, "paperTradingPerformanceGateHealth", "PAPER_TRADING_PERFORMANCE_GATE_HEALTH", "logs/paper_trading_performance_gate_health.properties");
        addArtifactPath(paths, "regimeStrategyPolicy", "REGIME_STRATEGY_POLICY_PATH", "logs/regime_strategy_policy.properties");
        addArtifactPath(paths, "regimeStrategyReport", "REGIME_STRATEGY_REPORT_PATH", "logs/regime_strategy_report.txt");
        addArtifactPath(paths, "regimeStrategyMatrix", "REGIME_STRATEGY_MATRIX_PATH", "logs/regime_strategy_matrix.csv");
        addArtifactPath(paths, "regimeStrategyHealth", "REGIME_STRATEGY_HEALTH_PATH", "logs/regime_strategy_health.properties");
        addArtifactPath(paths, "liveTradeReadinessHealth", "LIVE_TRADE_READINESS_HEALTH_PATH", "logs/live_trade_readiness_health.properties");
        addArtifactPath(paths, "liveTradeReadinessReport", "LIVE_TRADE_READINESS_REPORT_PATH", "logs/live_trade_readiness_report.txt");
        addArtifactPath(paths, "liveTradeReadinessJournal", "LIVE_TRADE_READINESS_JOURNAL_PATH", "logs/live_trade_readiness_journal.csv");
        addArtifactPath(paths, "shadowTradeDecisions", "SHADOW_TRADE_DECISION_PATH", "logs/shadow_trade_decisions.csv");
        addArtifactPath(paths, "shadowTradeOutcomes", "SHADOW_TRADE_OUTCOME_PATH", "logs/shadow_trade_outcomes.csv");
        addArtifactPath(paths, "supervisorStatus", "AUTONOMOUS_SUPERVISOR_STATUS", "logs/autonomous_supervisor_status.properties");
        addArtifactPath(paths, "supervisorJournal", "AUTONOMOUS_SUPERVISOR_JOURNAL", "logs/autonomous_supervisor_journal.csv");

        boolean passed = true;
        for (Map.Entry<String, Path> entry : paths.entrySet()) {
            try {
                Path path = entry.getValue();
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                boolean exists = Files.exists(path);
                artifactChecks.add(entry.getKey() + " path=" + path + " parentReady=true exists=" + exists + " status=PASS");
                if (!exists) {
                    warnings.add(entry.getKey() + " is not present yet; parent path is ready: " + path);
                }
            } catch (Exception e) {
                passed = false;
                String message = entry.getKey() + " path check failed: " + e.getMessage();
                artifactChecks.add(message + " status=FAIL");
                failures.add(message);
            }
        }
        return passed;
    }

    private static boolean requireEnv(Map<String, String> env,
                                      String key,
                                      String expected,
                                      String scope,
                                      List<String> checks,
                                      List<String> failures) {
        String actual = env.get(key);
        boolean passed = expected.equals(actual);
        checks.add(scope + "." + key + " expected=" + expected + " actual=" + actual + " status=" + status(passed));
        if (!passed) {
            failures.add(scope + " environment expected " + key + "=" + expected + " but got " + actual);
        }
        return passed;
    }

    private static boolean requireNonBlank(Map<String, String> env,
                                           String key,
                                           String scope,
                                           List<String> checks,
                                           List<String> failures) {
        String actual = env.get(key);
        boolean passed = actual != null && !actual.isBlank();
        checks.add(scope + "." + key + " value=" + actual + " status=" + status(passed));
        if (!passed) {
            failures.add(scope + " environment missing nonblank " + key);
        }
        return passed;
    }

    private static void addArtifactPath(Map<String, Path> paths, String label, String envKey, String fallback) {
        paths.put(label, Path.of(env(envKey, fallback)));
    }

    private static void writeReport(Path reportPath,
                                    String overallStatus,
                                    ZoneId zone,
                                    LocalTime restartTime,
                                    LocalTime shutdownTime,
                                    LocalTime expectedStart,
                                    LocalTime expectedEnd,
                                    Map<String, String> checkStatus,
                                    List<String> phaseChecks,
                                    List<String> environmentChecks,
                                    List<String> artifactChecks,
                                    List<String> timeline,
                                    List<String> warnings,
                                    List<String> failures) throws Exception {
        ensureParent(reportPath);
        StringBuilder report = new StringBuilder();
        report.append("Autonomous Ops Validation Report").append(System.lineSeparator());
        report.append("generatedAt=").append(ZonedDateTime.now(zone)).append(System.lineSeparator());
        report.append("status=").append(overallStatus).append(System.lineSeparator());
        report.append("scheduleZone=").append(zone).append(System.lineSeparator());
        report.append("configuredTradingWindow=").append(restartTime).append('-').append(shutdownTime).append(System.lineSeparator());
        report.append("expectedTradingWindow=").append(expectedStart).append('-').append(expectedEnd).append(System.lineSeparator());
        report.append("liveOrdersPlaced=false").append(System.lineSeparator());
        report.append("childProcessesStarted=false").append(System.lineSeparator());
        report.append("validationMode=dry_run_handoff_simulation").append(System.lineSeparator()).append(System.lineSeparator());

        appendSection(report, "Check Summary", mapLines(checkStatus));
        appendSection(report, "Phase Checks", phaseChecks);
        appendSection(report, "Environment Checks", environmentChecks);
        appendSection(report, "Compressed Cycle Timeline", timeline);
        appendSection(report, "Artifact Path Checks", artifactChecks);
        appendSection(report, "Warnings", warnings.isEmpty() ? List.of("none") : warnings);
        appendSection(report, "Failures", failures.isEmpty() ? List.of("none") : failures);

        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void writeHealth(Path healthPath,
                                    String overallStatus,
                                    ZoneId zone,
                                    LocalTime restartTime,
                                    LocalTime shutdownTime,
                                    Map<String, String> checkStatus,
                                    Path reportPath,
                                    Path journalPath,
                                    List<String> warnings,
                                    List<String> failures) throws Exception {
        ensureParent(healthPath);
        Properties properties = new Properties();
        properties.setProperty("status", overallStatus);
        properties.setProperty("scheduleZone", zone.toString());
        properties.setProperty("tradingWindow", restartTime + "-" + shutdownTime);
        properties.setProperty("alwaysOnSupervisor", "true");
        properties.setProperty("liveMain", ScheduledAutonomousTradingSupervisorMain.liveMainClassName());
        properties.setProperty("offlineImprovementMain", ScheduledAutonomousTradingSupervisorMain.evolutionMainClassName());
        properties.setProperty("reportPath", reportPath.toString());
        properties.setProperty("journalPath", journalPath.toString());
        properties.setProperty("failureCount", String.valueOf(failures.size()));
        properties.setProperty("warningCount", String.valueOf(warnings.size()));
        for (Map.Entry<String, String> entry : checkStatus.entrySet()) {
            properties.setProperty("check." + entry.getKey(), entry.getValue());
        }

        StringWriter writer = new StringWriter();
        properties.store(writer, "Autonomous ops validation health");
        Files.writeString(healthPath, writer.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void appendSection(StringBuilder report, String title, List<String> lines) {
        report.append(title).append(System.lineSeparator());
        for (String line : lines) {
            report.append("- ").append(line).append(System.lineSeparator());
        }
        report.append(System.lineSeparator());
    }

    private static List<String> mapLines(Map<String, String> values) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            lines.add(entry.getKey() + "=" + entry.getValue());
        }
        return lines;
    }

    private static ZonedDateTime nextWindowStart(ZoneId zone, LocalTime restartTime) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = now.with(restartTime);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return next;
    }

    private static void ensureParent(Path path) throws Exception {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static String status(boolean passed) {
        return passed ? PASS : FAIL;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value.trim())
                || "1".equals(value.trim())
                || "yes".equalsIgnoreCase(value.trim());
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static LocalTime parseLocalTime(String value, LocalTime fallback) {
        try {
            return value == null || value.isBlank() ? fallback : LocalTime.parse(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class ValidationResult {
        private final String status;
        private final Path reportPath;
        private final Path healthPath;
        private final Path journalPath;

        private ValidationResult(String status, Path reportPath, Path healthPath, Path journalPath) {
            this.status = status;
            this.reportPath = reportPath;
            this.healthPath = healthPath;
            this.journalPath = journalPath;
        }
    }
}
