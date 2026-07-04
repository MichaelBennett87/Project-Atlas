package com.bot;

import com.bot.governance.ImmutableSafetyRules;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Preferred always-on runner.
 *
 * This is the only main that should normally be launched manually.
 *
 * It owns the full autonomous trading day:
 *
 * 1. Waits for the configured live-session start when launched outside trading hours.
 * 2. Starts UnifiedStrategyMain for live trading.
 * 3. Stops the live engine at the configured evening shutdown time.
 * 4. Runs AutonomousCodeEvolutionMain offline for historical research, replay,
 *    OpenAI entry/exit policy improvement, validation-gated promotion, and
 *    optional guarded source evolution.
 * 5. Waits for the next restart time and repeats.
 *
 * The supervisor process itself stays alive 24/7. Only the child live-trading
 * process is cycled so after-hours improvement can run without the live lock.
 *
 * Live Java source rewriting is intentionally not performed by this supervisor.
 * The nightly process can promote policy/config improvements automatically only
 * after validation. Deeper Java source evolution remains after-hours and
 * validation-gated inside AutonomousCodeEvolutionMain.
 */
public class ScheduledAutonomousTradingSupervisorMain {

    private static final String LIVE_MAIN = "com.bot.UnifiedStrategyMain";
    private static final String EVOLUTION_MAIN = "com.bot.intelligence.AutonomousCodeEvolutionMain";
    private static final AtomicBoolean SHUTTING_DOWN = new AtomicBoolean(false);
    private static final AutonomousSupervisorJournal JOURNAL = new AutonomousSupervisorJournal();
    private static volatile Process liveProcess;
    private static volatile Process evolutionProcess;

    public static void main(String[] args) throws Exception {
        ZoneId zone = ImmutableSafetyRules.scheduleZone();
        LocalTime shutdownTime = ImmutableSafetyRules.nightlyShutdownTime();
        LocalTime restartTime = ImmutableSafetyRules.nextDayRestartTime();

        System.out.println("ScheduledAutonomousTradingSupervisorMain started.");
        System.out.println("THIS IS THE PREFERRED ALWAYS-ON MAIN. Do not separately run UnifiedStrategyMain while this is active.");
        System.out.println("scheduleZone=" + zone);
        System.out.println("liveMain=" + LIVE_MAIN);
        System.out.println("evolutionMain=" + EVOLUTION_MAIN);
        System.out.println("tradingWindowStart=" + restartTime);
        System.out.println("tradingWindowEnd=" + shutdownTime);
        System.out.println("alwaysOnSupervisor=true");
        System.out.println("nightlyLoop=liveSession->offlineResearch->policyValidation->promotionIfBetter->nextSession");
        installSupervisorShutdownHook();
        writeSupervisorStatus("STARTED", zone, restartTime, shutdownTime, Map.of("preferredMain", ScheduledAutonomousTradingSupervisorMain.class.getName()));

        while (!SHUTTING_DOWN.get()) {
            ZonedDateTime now = ZonedDateTime.now(zone);
            SessionPhase phase = phaseFor(now, restartTime, shutdownTime);

            if (phase == SessionPhase.BEFORE_LIVE_START) {
                ZonedDateTime nextStart = now.with(restartTime);
                System.out.println("SUPERVISOR PHASE: BEFORE_LIVE_START waitingUntil=" + nextStart);
                JOURNAL.event("BEFORE_LIVE_START", "WAITING", Map.of("nextStart", nextStart.toString()));
                waitUntil(nextStart);
                continue;
            }

            if (phase == SessionPhase.AFTER_LIVE_SHUTDOWN) {
                runOfflineImprovementCycle(zone, restartTime, shutdownTime, "launched_after_shutdown_or_live_session_finished");
                ZonedDateTime nextStart = nextOccurrence(zone, restartTime);
                System.out.println("NEXT LIVE RESTART SCHEDULED: " + nextStart);
                JOURNAL.event("NEXT_LIVE_RESTART", "WAITING", Map.of("nextStart", nextStart.toString()));
                waitUntil(nextStart);
                continue;
            }

            runLiveSessionThenNightlyCycle(zone, restartTime, shutdownTime);
        }

        writeSupervisorStatus("STOPPED", zone, restartTime, shutdownTime, Map.of());
        System.out.println("ScheduledAutonomousTradingSupervisorMain stopped.");
    }

    private static void runLiveSessionThenNightlyCycle(ZoneId zone, LocalTime restartTime, LocalTime shutdownTime) throws Exception {
        cleanupStaleTradingProcesses(LIVE_MAIN);
        cleanupStaleTradingProcesses(EVOLUTION_MAIN);

        ZonedDateTime nextShutdown = ZonedDateTime.now(zone).with(shutdownTime);
        if (!nextShutdown.isAfter(ZonedDateTime.now(zone))) {
            nextShutdown = nextShutdown.plusDays(1);
        }

        writeSupervisorStatus("LIVE_STARTING", zone, restartTime, shutdownTime, Map.of("scheduledShutdown", nextShutdown.toString()));
        Process live = startJavaMain(LIVE_MAIN, liveEnvironment(nextShutdown, restartTime, shutdownTime));
        liveProcess = live;
        System.out.println("LIVE PROCESS STARTED pid=" + live.pid() + " scheduledShutdown=" + nextShutdown);
        JOURNAL.event("LIVE", "STARTED", Map.of("pid", String.valueOf(live.pid()), "scheduledShutdown", nextShutdown.toString()));

        waitUntilOrProcessExit(nextShutdown, live);
        if (SHUTTING_DOWN.get()) {
            stopProcess(live, "supervisor shutdown");
            return;
        }

        if (live.isAlive()) {
            requestGracefulLiveShutdown(nextShutdown, "scheduled_evening_transition_to_offline_learning");
            stopProcess(live, "nightly scheduled transition to offline improvement");
        } else {
            System.out.println("LIVE PROCESS EXITED BEFORE SCHEDULED SHUTDOWN exitCode=" + live.exitValue());
            JOURNAL.event("LIVE", "EXITED_BEFORE_SHUTDOWN", Map.of("exitCode", String.valueOf(live.exitValue())));
        }
        liveProcess = null;

        runOfflineImprovementCycle(zone, restartTime, shutdownTime, "normal_post_live_session");
    }

    private static void runOfflineImprovementCycle(ZoneId zone, LocalTime restartTime, LocalTime shutdownTime, String reason) throws Exception {
        if (SHUTTING_DOWN.get()) {
            return;
        }

        cleanupStaleTradingProcesses(LIVE_MAIN);
        writeSupervisorStatus("OFFLINE_IMPROVEMENT_STARTING", zone, restartTime, shutdownTime, Map.of("reason", reason));
        System.out.println("SUPERVISOR PHASE: OFFLINE_IMPROVEMENT reason=" + reason);
        System.out.println("OFFLINE IMPROVEMENT PIPELINE: historicalResearch->replayLab->OpenAIEntryExitReview->policyPromotionValidation->marketRepresentation->sourceEvolutionIfAllowed->postValidation");
        JOURNAL.event("OFFLINE_IMPROVEMENT", "STARTING", Map.of("reason", reason));

        Process evolution = startJavaMain(EVOLUTION_MAIN, evolutionEnvironment(restartTime, shutdownTime));
        evolutionProcess = evolution;
        int exit = evolution.waitFor();
        evolutionProcess = null;
        System.out.println("AUTONOMOUS EVOLUTION FINISHED exitCode=" + exit);
        JOURNAL.event("OFFLINE_IMPROVEMENT", exit == 0 ? "FINISHED" : "FAILED", Map.of("exitCode", String.valueOf(exit)));
        writeSupervisorStatus(exit == 0 ? "OFFLINE_IMPROVEMENT_FINISHED" : "OFFLINE_IMPROVEMENT_FAILED",
                zone, restartTime, shutdownTime, Map.of("exitCode", String.valueOf(exit)));

        if (exit != 0 && "true".equalsIgnoreCase(System.getenv().getOrDefault("SUPERVISOR_FAIL_CLOSED_ON_EVOLUTION_ERROR", "false"))) {
            throw new IllegalStateException("Autonomous evolution failed and SUPERVISOR_FAIL_CLOSED_ON_EVOLUTION_ERROR=true; refusing automatic restart.");
        }
    }

    private static void installSupervisorShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!SHUTTING_DOWN.compareAndSet(false, true)) {
                return;
            }
            System.out.println("SUPERVISOR SHUTDOWN REQUESTED: stopping child Java processes before IntelliJ exits.");
            JOURNAL.event("SUPERVISOR", "SHUTDOWN_REQUESTED", Map.of("reason", "JVM shutdown"));
            stopProcess(liveProcess, "IntelliJ/application shutdown");
            stopProcess(evolutionProcess, "IntelliJ/application shutdown");
        }, "scheduled-supervisor-child-cleanup"));
    }

    private static void cleanupStaleTradingProcesses(String className) {
        if (!"true".equalsIgnoreCase(System.getenv().getOrDefault("KILL_STALE_TRADING_BOT_PROCESSES", "true"))) {
            return;
        }
        long currentPid = ProcessHandle.current().pid();
        ProcessHandle.allProcesses().forEach(handle -> {
            if (handle.pid() == currentPid) {
                return;
            }
            try {
                String commandLine = handle.info().commandLine().orElse("");
                if (!commandLine.contains(className)) {
                    return;
                }
                System.out.println("STALE TRADING PROCESS FOUND: pid=" + handle.pid() + " class=" + className + "; requesting shutdown before continuing supervisor cycle.");
                JOURNAL.event("STALE_PROCESS", "DESTROY", Map.of("pid", String.valueOf(handle.pid()), "className", className));
                handle.destroy();
                try {
                    handle.onExit().get(10, TimeUnit.SECONDS);
                } catch (Exception timeout) {
                    System.out.println("STALE TRADING PROCESS DID NOT EXIT; forcing pid=" + handle.pid());
                    handle.destroyForcibly();
                }
            } catch (Exception ignored) {
            }
        });
    }

    private static Process startJavaMain(String className, Map<String, String> extraEnv) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(className);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.inheritIO();
        pb.environment().putAll(extraEnv);
        return pb.start();
    }

    public static String liveMainClassName() {
        return LIVE_MAIN;
    }

    public static String evolutionMainClassName() {
        return EVOLUTION_MAIN;
    }

    public static String phaseNameFor(ZonedDateTime now, LocalTime restartTime, LocalTime shutdownTime) {
        return phaseFor(now, restartTime, shutdownTime).name();
    }

    public static Map<String, String> validationLiveEnvironment(ZonedDateTime scheduledShutdown, LocalTime restartTime, LocalTime shutdownTime) {
        return new LinkedHashMap<>(liveEnvironment(scheduledShutdown, restartTime, shutdownTime));
    }

    public static Map<String, String> validationEvolutionEnvironment(LocalTime restartTime, LocalTime shutdownTime) {
        return new LinkedHashMap<>(evolutionEnvironment(restartTime, shutdownTime));
    }

    private static Map<String, String> liveEnvironment(ZonedDateTime scheduledShutdown, LocalTime restartTime, LocalTime shutdownTime) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("AUTO_SHUTDOWN_AT_8PM", "false");
        env.put("SUPERVISOR_MANAGED_SESSION", "true");
        env.put("SUPERVISOR_SCHEDULED_SHUTDOWN", scheduledShutdown.toString());
        env.put("AUTONOMOUS_RESTART_TIME", restartTime.toString());
        env.put("AUTONOMOUS_SHUTDOWN_TIME", shutdownTime.toString());
        env.put("AUTONOMOUS_SCHEDULE_ZONE", ImmutableSafetyRules.scheduleZone().toString());
        env.put("NEWS_BOT_EXTENDED_HOURS_BUY_ENABLED", System.getenv().getOrDefault("NEWS_BOT_EXTENDED_HOURS_BUY_ENABLED", "true"));
        env.put("ALPACA_NEWS_WS_LOCK_PATH", "logs/alpaca_news_ws.lock");
        env.put("LIVE_TRADING_LOCK_PATH", "logs/live_trading.lock");
        env.put("ENTRY_EXIT_POLICY_AUTO_LOAD", "true");
        env.put("OPENAI_ENTRY_EXIT_GOVERNOR_ENABLED", System.getenv().getOrDefault("OPENAI_ENTRY_EXIT_GOVERNOR_ENABLED", "true"));
        env.put("AUTONOMOUS_POLICY_PROMOTION_ENABLED", System.getenv().getOrDefault("AUTONOMOUS_POLICY_PROMOTION_ENABLED", "true"));
        return env;
    }

    private static Map<String, String> evolutionEnvironment(LocalTime restartTime, LocalTime shutdownTime) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("TRADING_ENABLED", "false");
        env.put("DRY_RUN", "true");
        env.put("AUTO_SHUTDOWN_AT_8PM", "false");
        env.put("SUPERVISOR_MANAGED_SESSION", "true");
        env.put("OFFLINE_AUTONOMOUS_EVOLUTION", "true");
        env.put("AUTONOMOUS_RESTART_TIME", restartTime.toString());
        env.put("AUTONOMOUS_SHUTDOWN_TIME", shutdownTime.toString());
        env.put("AUTONOMOUS_SCHEDULE_ZONE", ImmutableSafetyRules.scheduleZone().toString());
        env.put("OPENAI_ENTRY_EXIT_GOVERNOR_ENABLED", "true");
        env.put("AUTONOMOUS_POLICY_PROMOTION_ENABLED", "true");
        env.put("AUTONOMOUS_SOURCE_REWRITE_LIVE", "false");
        env.put("REQUIRE_POLICY_VALIDATION_BEFORE_PROMOTION", "true");
        env.put("LIVE_TRADING_LOCK_PATH", "logs/live_trading.lock");
        return env;
    }

    private static void requestGracefulLiveShutdown(ZonedDateTime shutdownTime, String reason) {
        try {
            Path path = Path.of(System.getenv().getOrDefault("SUPERVISOR_SHUTDOWN_INTENT_PATH", "logs/supervisor_shutdown_intent.properties"));
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String content = "reason=" + reason + System.lineSeparator()
                    + "requestedAt=" + ZonedDateTime.now(shutdownTime.getZone()) + System.lineSeparator()
                    + "scheduledShutdown=" + shutdownTime + System.lineSeparator()
                    + "allowNewEntries=false" + System.lineSeparator()
                    + "handoffToOfflineEvolution=true" + System.lineSeparator();
            Files.writeString(path, content, StandardCharsets.UTF_8);
            System.out.println("SUPERVISOR SHUTDOWN INTENT WRITTEN: " + path + " reason=" + reason);
            JOURNAL.event("LIVE", "SHUTDOWN_INTENT_WRITTEN", Map.of("path", path.toString(), "reason", reason));
            Thread.sleep(envLong("SUPERVISOR_GRACEFUL_SHUTDOWN_SIGNAL_MS", 5_000L));
        } catch (Exception e) {
            System.err.println("SUPERVISOR SHUTDOWN INTENT ERROR: " + e.getMessage());
        }
    }

    private static void stopProcess(Process process, String reason) {
        if (process == null || !process.isAlive()) {
            return;
        }

        System.out.println("STOPPING CHILD PROCESS pid=" + process.pid() + " reason=" + reason);
        JOURNAL.event("CHILD_PROCESS", "STOPPING", Map.of("pid", String.valueOf(process.pid()), "reason", reason));
        destroyProcessTree(process, false);
        try {
            if (!process.waitFor(envLong("SUPERVISOR_CHILD_GRACEFUL_EXIT_SECONDS", 45L), TimeUnit.SECONDS)) {
                System.out.println("CHILD PROCESS DID NOT EXIT GRACEFULLY; forcing shutdown pid=" + process.pid());
                JOURNAL.event("CHILD_PROCESS", "FORCING", Map.of("pid", String.valueOf(process.pid()), "reason", reason));
                destroyProcessTree(process, true);
                process.waitFor(15, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyProcessTree(process, true);
        }
    }

    private static void destroyProcessTree(Process process, boolean forcibly) {
        if (process == null) {
            return;
        }
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(child -> {
            try {
                if (forcibly) {
                    child.destroyForcibly();
                } else {
                    child.destroy();
                }
            } catch (Exception ignored) {
            }
        });
        try {
            if (forcibly) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        } catch (Exception ignored) {
        }
    }

    private static ZonedDateTime nextOccurrence(ZoneId zone, LocalTime time) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = now.with(time);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return next;
    }

    private static void waitUntil(ZonedDateTime target) throws InterruptedException {
        while (!SHUTTING_DOWN.get()) {
            long millis = Duration.between(ZonedDateTime.now(target.getZone()), target).toMillis();
            if (millis <= 0) {
                return;
            }
            Thread.sleep(Math.min(millis, 60_000L));
        }
    }

    private static void waitUntilOrProcessExit(ZonedDateTime target, Process process) throws InterruptedException {
        while (!SHUTTING_DOWN.get()) {
            if (process != null && !process.isAlive()) {
                return;
            }
            long millis = Duration.between(ZonedDateTime.now(target.getZone()), target).toMillis();
            if (millis <= 0) {
                return;
            }
            Thread.sleep(Math.min(millis, 30_000L));
        }
    }

    private static SessionPhase phaseFor(ZonedDateTime now, LocalTime restartTime, LocalTime shutdownTime) {
        LocalTime current = now.toLocalTime();
        if (restartTime.equals(shutdownTime)) {
            return SessionPhase.LIVE_SESSION;
        }
        if (restartTime.isBefore(shutdownTime)) {
            if (current.isBefore(restartTime)) {
                return SessionPhase.BEFORE_LIVE_START;
            }
            if (!current.isBefore(shutdownTime)) {
                return SessionPhase.AFTER_LIVE_SHUTDOWN;
            }
            return SessionPhase.LIVE_SESSION;
        }
        // Overnight live window, e.g. start 20:00 and stop 04:00.
        if (!current.isBefore(restartTime) || current.isBefore(shutdownTime)) {
            return SessionPhase.LIVE_SESSION;
        }
        return SessionPhase.AFTER_LIVE_SHUTDOWN;
    }

    private static void writeSupervisorStatus(String status, ZoneId zone, LocalTime restartTime, LocalTime shutdownTime, Map<String, String> extra) {
        try {
            Path path = Path.of(System.getenv().getOrDefault("AUTONOMOUS_SUPERVISOR_STATUS", "logs/autonomous_supervisor_status.properties"));
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("status", status);
            fields.put("timestamp", ZonedDateTime.now(zone).toString());
            fields.put("scheduleZone", zone.toString());
            fields.put("restartTime", restartTime.toString());
            fields.put("shutdownTime", shutdownTime.toString());
            fields.put("tradingWindow", restartTime + "-" + shutdownTime);
            fields.put("alwaysOnSupervisor", "true");
            fields.put("liveMain", LIVE_MAIN);
            fields.put("evolutionMain", EVOLUTION_MAIN);
            fields.put("currentDate", LocalDate.now(zone).toString());
            fields.putAll(extra);
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                out.append(entry.getKey()).append('=').append(entry.getValue()).append(System.lineSeparator());
            }
            Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
            JOURNAL.event("SUPERVISOR", status, fields);
        } catch (Exception e) {
            System.err.println("SUPERVISOR STATUS WRITE ERROR: " + e.getMessage());
        }
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            return "java";
        }
        return javaHome + File.separator + "bin" + File.separator + "java";
    }

    private enum SessionPhase {
        BEFORE_LIVE_START,
        LIVE_SESSION,
        AFTER_LIVE_SHUTDOWN
    }
}
